"""Verify conversational meal planning against a real model, with token-free evidence under logs/."""

import sys as _auth_sys
from pathlib import Path as _AuthPath
_auth_sys.path.insert(0, str(_AuthPath(__file__).resolve().parents[2] / 'tools'))
import cookie_http as _auth_http
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]


def request(base, path, method="GET", body=None, token=None, expected=200):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(base + path, method=method, headers=headers,
                                 data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None)
    try:
        with _auth_http.urlopen(req, timeout=150) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    if status != expected:
        raise RuntimeError(f"{method} {path}: expected HTTP {expected}, got {status}")
    return json.loads(raw) if raw else None


def chat(base, token, question, conversation=None):
    # Send no client-side history: follow-ups must work from the backend's persisted conversation.
    body = {"question": question, "conversationId": conversation, "history": []}
    req = urllib.request.Request(base + "/api/assistant/stream", method="POST",
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json",
                                          "Accept": "text/event-stream"}, data=json.dumps(body, ensure_ascii=False).encode())
    started = time.monotonic()
    with _auth_http.urlopen(req, timeout=150) as response:
        if "text/event-stream" not in response.headers.get("Content-Type", ""):
            raise RuntimeError("Expected the real browser SSE endpoint")
        raw = response.read().decode("utf-8").replace("\r\n", "\n")
    events, result = [], None
    for block in raw.split("\n\n"):
        event, data = "message", []
        for line in block.splitlines():
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data.append(line[5:].lstrip())
        if not data:
            continue
        value = json.loads("\n".join(data))
        events.append(event)
        if event == "error":
            raise RuntimeError("Assistant SSE did not complete: " + value.get("code", "ERROR"))
        if event == "complete":
            result = value
    if result is None:
        raise RuntimeError("Assistant SSE ended without a completion event")
    return {"question": question, "durationMs": round((time.monotonic() - started) * 1000),
            "events": events, "response": result}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def check_recommendation(base, token, turn, expected, empty=False):
    calls = [call for call in turn["response"].get("toolCalls", []) if call["name"] == "recommend_next_meal"]
    require(len(calls) == 1, "Expected exactly one real recommendation tool call")
    call = calls[0]
    require(call["origin"] == "MODEL" and call["status"] == "SUCCESS", "The model must originate a successful tool call")
    require(json.loads(call["arguments"]) == expected, "Model arguments do not match user constraints")
    reference = request(base, "/api/meals/next-meal", "POST", expected, token)
    require(bool(reference["options"]) != empty, "Unexpected real catalog result")
    require(f"找到 {len(reference['options'])} 套搭配" in call["resultSummary"], "Tool trace does not match the real catalog")
    if empty:
        require(reference["message"] in turn["response"]["answer"], "No-match response was replaced by a made-up recommendation")
    turn["catalogVerification"] = reference
    turn["passed"] = True


def env_file():
    values = {}
    path = ROOT / ".env"
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            if line.strip() and not line.lstrip().startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip("\"'")
    return values


def docker(*args, env=None):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, encoding="utf-8", errors="replace", env=env, timeout=60)
    if result.returncode:
        raise RuntimeError("Docker command failed: " + " ".join(args[:2]) + ": " + result.stderr.strip()[:400])
    return result.stdout.strip()


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class IsolatedRuntime:
    def __init__(self, output, report):
        self.output, self.report = output, report
        self.containers, self.process, self.stream = [], None, None

    def start(self):
        settings = env_file()
        model = {key: os.environ.get(key) or settings.get(key, "") for key in
                 ("MEALCHECK_AI_API_KEY", "MEALCHECK_AI_BASE_URL", "MEALCHECK_AI_MODEL")}
        require(all(model.values()), "A model URL, model name and API key must be configured")
        jar = ROOT / "backend/target/mealcheck-backend.jar"
        require(jar.is_file(), "Build the current backend JAR before running this test")
        self.report.update({"model": model["MEALCHECK_AI_MODEL"], "providerBaseUrl": model["MEALCHECK_AI_BASE_URL"],
                            "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "runtime": "isolated-native-backend-and-temporary-containers"})
        suffix = uuid.uuid4().hex[:10]
        postgres, redis = "mealcheck-fc-pg-" + suffix, "mealcheck-fc-redis-" + suffix
        pg_env = dict(os.environ, POSTGRES_DB="mealcheck", POSTGRES_USER="mealcheck", POSTGRES_PASSWORD=uuid.uuid4().hex)
        docker("run", "-d", "--pull=never", "--name", postgres, "-p", "127.0.0.1::5432",
               "-e", "POSTGRES_DB", "-e", "POSTGRES_USER", "-e", "POSTGRES_PASSWORD", "pgvector/pgvector:pg16", env=pg_env)
        self.containers.append(postgres)
        docker("run", "-d", "--pull=never", "--name", redis, "-p", "127.0.0.1::6379", "redis:7-alpine")
        self.containers.append(redis)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            try:
                docker("exec", postgres, "pg_isready", "-U", "mealcheck")
                break
            except RuntimeError:
                time.sleep(1)
        else:
            raise RuntimeError("Temporary PostgreSQL did not become ready")
        pg_port = docker("port", postgres, "5432/tcp").rsplit(":", 1)[1]
        redis_port = docker("port", redis, "6379/tcp").rsplit(":", 1)[1]
        port = free_port()
        base = f"http://127.0.0.1:{port}"
        crash = ROOT / "logs/crash"
        crash.mkdir(parents=True, exist_ok=True)
        upload = self.output / "uploads"
        upload.mkdir()
        backend_env = dict(os.environ, **model)
        backend_env.update({
            "SERVER_PORT": str(port), "SPRING_PROFILES_ACTIVE": "live-verification",
            "SPRING_DATASOURCE_URL": f"jdbc:postgresql://127.0.0.1:{pg_port}/mealcheck",
            "SPRING_DATASOURCE_USERNAME": "mealcheck", "SPRING_DATASOURCE_PASSWORD": pg_env["POSTGRES_PASSWORD"],
            "SPRING_REDIS_HOST": "127.0.0.1", "SPRING_REDIS_PORT": redis_port, "SPRING_REDIS_PASSWORD": "",
            "MEALCHECK_JWT_SECRET": uuid.uuid4().hex + uuid.uuid4().hex,
            "MEALCHECK_ASSISTANT_FUNCTION_CALLING_ENABLED": "true", "MEALCHECK_KNOWLEDGE_AUTO_INDEX": "false",
            "MEALCHECK_KNOWLEDGE_RERANK_ENABLED": "false", "MEALCHECK_ANALYSIS_DISPATCH": "polling",
            "MEALCHECK_ANALYSIS_WORKER_ENABLED": "false", "MEALCHECK_WEEKLY_REPORT_MULTI_AGENT_ENABLED": "false",
            "MEALCHECK_RETENTION_ENABLED": "false", "MEALCHECK_MCP_ENABLED": "false",
            "MANAGEMENT_HEALTH_RABBIT_ENABLED": "false", "SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP": "false",
            "MEALCHECK_UPLOAD_DIR": str(upload), "MEALCHECK_UPLOAD_TMP_DIR": str(upload / "tmp"),
            "MEALCHECK_AI_DAILY_CALLS": "12", "MEALCHECK_REMOTE_MAX_ATTEMPTS": "1",
            "JAVA_TOOL_OPTIONS": "-Xmx768m -XX:ErrorFile=" + (crash / "hs_err_pid%p.log").as_posix(),
        })
        self.stream = (self.output / "backend.log").open("xb")
        self.process = subprocess.Popen([shutil.which("java"), "-Dfile.encoding=UTF-8", "-jar", str(jar)],
                                        cwd=ROOT, env=backend_env, stdout=self.stream, stderr=subprocess.STDOUT,
                                        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        self.report["endpoint"] = base
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError("Isolated backend exited; inspect backend.log")
            try:
                if request(base, "/actuator/health")["status"] == "UP":
                    print("Isolated backend ready; configured model: " + self.report["model"], flush=True)
                    return base
            except (OSError, RuntimeError):
                time.sleep(1)
        raise RuntimeError("Isolated backend readiness timed out; inspect backend.log")

    def close(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        if self.stream:
            self.stream.close()
        removed = []
        for name in reversed(self.containers):
            docker("rm", "-f", "-v", name)
            removed.append(name)
        self.report["temporaryRuntimeRemoved"] = len(removed) == len(self.containers)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--isolated", action="store_true", help="Use a fresh database and the configured real model")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = (args.output or ROOT / "logs/backend" / ("function-calling-live-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f"))).resolve()
    require(output.is_relative_to((ROOT / "logs").resolve()) and not output.exists(), "Use a fresh output directory under logs/")
    output.mkdir(parents=True)
    report = {"passed": False, "cases": [], "temporaryAccountDeleted": False, "realModel": True}
    runtime = IsolatedRuntime(output, report) if args.isolated else None
    base, token, password = args.base_url.rstrip("/"), None, None
    try:
        if runtime:
            base = runtime.start()
        captcha = request(base, "/api/auth/captcha")
        numbers = re.fullmatch(r"(\d+)\s*\+\s*(\d+)\s*=\s*\?", captcha["question"])
        require(numbers is not None, "Unexpected registration captcha")
        temporary_password = "FcLive-" + uuid.uuid4().hex
        auth = request(base, "/api/auth/register", "POST", {
            "username": "fc_live_" + uuid.uuid4().hex[:16], "displayName": "Temporary real-model verification",
            "password": temporary_password, "captchaId": captcha["captchaId"],
            "captchaAnswer": str(int(numbers[1]) + int(numbers[2]))})
        token, password = auth["token"], temporary_password
        constraints = {"mealType": "DINNER", "scene": "HOME", "budget": 25, "availableMinutes": 30}
        scenarios = [
            ("success", "晚餐在家做，预算25元，30分钟内准备好，推荐下一餐搭配。"),
            ("follow_up", "改成外卖，预算还是25元"),
            ("clarification", "推荐下一餐搭配"),
            ("no_match", "晚餐在家做，预算1元，5分钟内准备好，推荐下一餐搭配"),
        ]
        conversation = None
        for name, question in scenarios:
            print("Real-model case: " + name, flush=True)
            turn = {"case": name, **chat(base, token, question, conversation if name == "follow_up" else None)}
            report["cases"].append(turn)
            (output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
            if name == "success":
                check_recommendation(base, token, turn, constraints)
                conversation = turn["response"]["conversationId"]
            elif name == "follow_up":
                check_recommendation(base, token, turn, dict(constraints, scene="TAKEOUT"))
            elif name == "clarification":
                require(not turn["response"].get("toolCalls"), "Missing constraints must not be invented or queried")
                require(any(word in turn["response"]["answer"] for word in ("请", "预算", "时间", "餐次")), "Expected a clarification")
                turn["passed"] = True
            else:
                check_recommendation(base, token, turn, dict(constraints, budget=1, availableMinutes=5), empty=True)
            restored = request(base, "/api/assistant/conversations/" + str(turn["response"]["conversationId"]), token=token)
            require(restored["messages"][-1].get("toolCalls", []) == turn["response"].get("toolCalls", []), "Saved tool history differs from the actual response")
            turn["historyVerified"] = True
            print(name + ": passed", flush=True)
        report["requestBudget"] = request(base, "/api/profile/ai-budget", token=token)
        report["passed"] = all(case.get("passed") for case in report["cases"]) and len(report["cases"]) == 4
    except Exception as error:
        report["error"] = {"type": type(error).__name__, "message": str(error)}
        print("Verification failed: " + type(error).__name__ + ": " + str(error), flush=True)
    finally:
        try:
            if password:
                request(base, "/api/profile/account", "DELETE", {"password": password}, token, expected=204)
                request(base, "/api/meals", token=token, expected=401)
                report["temporaryAccountDeleted"] = True
        finally:
            try:
                if runtime:
                    runtime.close()
            finally:
                (output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
                print("Evidence: " + str(output), flush=True)
    return 0 if report["passed"] and report["temporaryAccountDeleted"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
