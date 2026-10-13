"""Run the repository's two Nginx proxy layers against a local HTTP fixture.

Usage: python tools/test_proxy_headers.py --nginx /path/to/nginx
The fixture records the sanitized headers and enforces a two-request IP quota.
No application data, credentials, or model calls are used.
"""
import argparse
import http.client
import json
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def run(nginx, slow_seconds=0):
    root = Path(__file__).resolve().parents[1]
    scratch = root / "backend" / "target" / "proxy-validation"
    scratch.mkdir(parents=True, exist_ok=True)
    counts = {}

    class Backend(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            if self.path == "/api/test/slow":
                time.sleep(slow_seconds)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"completed":true}')
                return
            if self.path == "/api/test/deadline":
                self.send_response(504)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"code":"AI_DEADLINE_EXCEEDED"}')
                return
            address = self.headers.get("X-Forwarded-For", "").split(",")[0].strip()
            counts[address] = counts.get(address, 0) + 1
            self.send_response(200 if counts[address] <= 2 else 429)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"address": address, "forwarded": self.headers.get("Forwarded"),
                                        "host": self.headers.get("X-Forwarded-Host"),
                                        "prefix": self.headers.get("X-Forwarded-Prefix"),
                                        "protocol": self.headers.get("X-Forwarded-Proto")}).encode())

    backend = ThreadingHTTPServer(("127.0.0.1", 0), Backend)
    thread = threading.Thread(target=backend.serve_forever, daemon=True)
    thread.start()
    outer_port, inner_port = port(), port()
    try:
        with tempfile.TemporaryDirectory(prefix="proxy-", dir=scratch) as directory:
            prefix = Path(directory)
            (prefix / "logs").mkdir()
            (prefix / "temp").mkdir()
            inner = (root / "frontend" / "nginx.conf").read_text(encoding="utf-8")
            inner = inner.replace("listen 8080 default_server;", f"listen 127.0.0.1:{inner_port} default_server;")
            inner = inner.replace("http://backend:8080", f"http://127.0.0.1:{backend.server_port}")
            inner = inner.replace("root /usr/share/nginx/html;", f'root "{prefix.as_posix()}";')
            outer = (root / "deploy" / "nginx" / "mealcheck.conf.example").read_text(encoding="utf-8")
            outer = outer[outer.index("server {", outer.index("server {") + 1):]
            outer = re.sub(r"    listen .*;\n", "", outer)
            outer = outer.replace("server {", f"server {{\n    listen 127.0.0.1:{outer_port};", 1)
            outer = re.sub(r"    ssl_[^\n]+\n", "", outer)
            outer = outer.replace("http://127.0.0.1:8080", f"http://127.0.0.1:{inner_port}")
            config = prefix / "nginx.conf"
            paths = f'pid "{prefix.as_posix()}/nginx.pid";\nerror_log "{prefix.as_posix()}/logs/error.log";\n'
            http_paths = f'access_log "{prefix.as_posix()}/logs/access.log";\n'
            for kind in ("client_body", "proxy", "fastcgi", "uwsgi", "scgi"):
                http_paths += f'{kind}_temp_path "{prefix.as_posix()}/temp/{kind}";\n'
            config.write_text(paths + "events { worker_connections 64; }\nhttp {\n" + http_paths + inner + "\n" + outer + "\n}\n", encoding="utf-8")
            args = [str(nginx), "-p", prefix.as_posix() + "/", "-c", config.as_posix()]
            hidden = subprocess.CREATE_NO_WINDOW if hasattr(subprocess, "CREATE_NO_WINDOW") else 0
            validation = subprocess.run(args + ["-t"], capture_output=True, creationflags=hidden)
            if validation.returncode:
                raise RuntimeError(validation.stderr.decode(errors="replace"))
            with (prefix / "process.log").open("w") as output:
                process = subprocess.Popen(args + ["-g", "daemon off;"], stdout=output, stderr=output, creationflags=hidden)
                try:
                    for _ in range(50):
                        if process.poll() is not None:
                            raise RuntimeError((prefix / "process.log").read_text())
                        try:
                            with socket.create_connection(("127.0.0.1", outer_port), timeout=.1):
                                break
                        except OSError:
                            time.sleep(.1)
                    observed = []
                    for attempt in range(4):
                        forged = f"198.51.100.{attempt + 1}"
                        conn = http.client.HTTPConnection("127.0.0.1", outer_port, timeout=5)
                        conn.request("GET", "/api/auth/captcha", headers={
                            "Forwarded": f"for={forged};proto=https;host=evil.invalid",
                            "X-Forwarded-For": forged, "X-Real-IP": forged,
                            "X-Forwarded-Host": "evil.invalid", "X-Forwarded-Proto": "https",
                            "X-Forwarded-Port": "443", "X-Forwarded-Prefix": "/spoof", "X-Forwarded-Ssl": "on"})
                        response = conn.getresponse()
                        body = json.loads(response.read())
                        observed.append(response.status)
                        assert body["address"] == "127.0.0.1", body
                        assert body["forwarded"] is None and body["prefix"] is None, body
                        assert body["host"] != "evil.invalid", body
                        conn.close()
                    assert observed == [200, 200, 429, 429], observed
                    # A client outside the explicitly trusted proxy channel cannot
                    # inject X-Real-IP directly into the inner proxy either.
                    conn = http.client.HTTPConnection("127.0.0.1", inner_port, timeout=5, source_address=("127.0.0.2", 0))
                    conn.request("GET", "/api/auth/captcha", headers={"X-Real-IP": "198.51.100.200", "X-Forwarded-For": "198.51.100.201", "X-Forwarded-Proto": "https"})
                    response = conn.getresponse()
                    body = json.loads(response.read())
                    assert body["address"] == "127.0.0.2" and body["protocol"] == "http", body
                    conn.close()
                    conn = http.client.HTTPConnection("127.0.0.1", outer_port, timeout=180)
                    conn.request("GET", "/api/test/deadline")
                    response = conn.getresponse()
                    assert response.status == 504 and json.loads(response.read())["code"] == "AI_DEADLINE_EXCEEDED"
                    conn.close()
                    if slow_seconds:
                        conn = http.client.HTTPConnection("127.0.0.1", outer_port, timeout=180)
                        conn.request("GET", "/api/test/slow")
                        response = conn.getresponse()
                        assert response.status == 200 and json.loads(response.read())["completed"] is True
                        conn.close()
                    return {"passed": True, "twoProxyLayers": True, "quotaStatuses": observed,
                            "untrustedInnerClientVerified": True, "backendDeadline504Preserved": True,
                            "slowResponseSeconds": slow_seconds}
                finally:
                    subprocess.run(args + ["-s", "quit"], capture_output=True, creationflags=hidden)
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.terminate()
                        process.wait(timeout=5)
    finally:
        backend.shutdown()
        backend.server_close()
        thread.join()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nginx", type=Path, required=True)
    parser.add_argument("--slow-seconds", type=float, default=0)
    args = parser.parse_args()
    if not 0 <= args.slow_seconds <= 120: parser.error("slow-seconds must be between 0 and 120")
    print(json.dumps(run(args.nginx.resolve(), args.slow_seconds)))
