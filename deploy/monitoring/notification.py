"""Private Alertmanager relay. Never logs webhook URLs, credentials or alert payloads."""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def payload(event, provider):
    lines = ["MealCheck " + ("恢复" if event.get("status") == "resolved" else "告警")]
    for alert in event.get("alerts", [])[:20]:
        labels, annotations = alert.get("labels", {}), alert.get("annotations", {})
        lines.append(f"[{labels.get('severity', 'warning')}] {annotations.get('summary', labels.get('alertname', 'unknown'))}")
        if annotations.get("description"): lines.append(annotations["description"])
    message = "\n".join(lines)[:4000]
    if provider == "wecom": return {"msgtype": "text", "text": {"content": message}}
    if provider == "dingtalk": return {"msgtype": "text", "text": {"content": message}}
    if provider == "generic": return {"status": event.get("status"), "message": message}
    raise ValueError("Unsupported notification provider")


def deliver(event, destination, provider):
    data = json.dumps(payload(event, provider), ensure_ascii=False).encode()
    with urlopen(Request(destination, data=data, headers={"Content-Type": "application/json"}), timeout=10) as response:
        body = response.read(8192)
        if provider != "generic":
            result = json.loads(body)
            if result.get("errcode", -1) != 0: raise RuntimeError("Notification provider rejected delivery")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        self.send_response(200 if self.path == "/health" else 404); self.end_headers()
    def do_POST(self):
        if self.path != "/alerts": self.send_response(404); self.end_headers(); return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 131072: raise ValueError("Invalid payload size")
            event = json.loads(self.rfile.read(size))
            deliver(event, os.environ["MEALCHECK_ALERT_WEBHOOK"], os.environ.get("MEALCHECK_ALERT_PROVIDER", "generic"))
            self.send_response(200)
        except Exception:
            # A non-2xx response makes Alertmanager retry the notification.
            self.send_response(502)
        self.end_headers()


def main():
    target = os.environ.get("MEALCHECK_ALERT_WEBHOOK", "")
    if urlparse(target).scheme != "https" or not urlparse(target).hostname:
        raise SystemExit("Configure MEALCHECK_ALERT_WEBHOOK as a valid HTTPS URL")
    payload({}, os.environ.get("MEALCHECK_ALERT_PROVIDER", "generic"))
    ThreadingHTTPServer(("0.0.0.0", 9080), Handler).serve_forever()


if __name__ == "__main__": main()
