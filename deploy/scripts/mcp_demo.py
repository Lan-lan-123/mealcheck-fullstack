"""Demonstrate the real MealCheck MCP endpoint without calling an AI model."""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.request
import uuid


def rest(base, path, method="GET", body=None, token=None, expected=200):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(
        base + path, method=method, headers=headers,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    if status != expected:
        raise RuntimeError(f"{method} {path}: expected {expected}, received {status}")
    return json.loads(raw) if raw else None


class McpClient:
    def __init__(self, base, token, steps):
        self.url = base + "/mcp"
        self.token = token
        self.steps = steps
        self.session = None
        self.protocol = None
        self.next_id = 0

    def headers(self):
        headers = {
            "Authorization": "Bearer " + self.token,
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        }
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        if self.protocol:
            headers["MCP-Protocol-Version"] = self.protocol
        return headers

    def request(self, method, params=None, notification=False):
        message = {"jsonrpc": "2.0", "method": method}
        if not notification:
            self.next_id += 1
            message["id"] = self.next_id
        if params is not None:
            message["params"] = params
        request = urllib.request.Request(
            self.url, method="POST", headers=self.headers(),
            data=json.dumps(message).encode("utf-8"),
        )
        with urllib.request.urlopen(request, timeout=20) as response:
            self.session = response.headers.get("Mcp-Session-Id") or self.session
            if notification or response.status == 202:
                reply = None
            elif "text/event-stream" in response.headers.get("Content-Type", ""):
                # Stop at this request's response; do not wait on an open SSE stream.
                reply, data = None, []
                for raw_line in response:
                    line = raw_line.decode("utf-8").rstrip("\r\n")
                    if line.startswith("data:"):
                        data.append(line[5:].lstrip())
                    elif not line and data:
                        event = json.loads("\n".join(data))
                        data = []
                        if event.get("id") == message.get("id"):
                            reply = event
                            break
                if reply is None:
                    raise RuntimeError(f"No MCP response for {method}")
            else:
                reply = json.loads(response.read())
            self.steps.append({"request": message, "httpStatus": response.status, "response": reply})
        if reply and reply.get("error"):
            raise RuntimeError(f"MCP {method} returned: {reply['error']}")
        return reply.get("result") if reply else None

    def initialize(self):
        result = self.request("initialize", {
            "protocolVersion": "2025-11-25",
            "capabilities": {},
            "clientInfo": {"name": "mealcheck-python-demo", "version": "1.0.0"},
        })
        self.protocol = result["protocolVersion"]
        self.request("notifications/initialized", notification=True)
        return result

    def call(self, name, arguments=None):
        result = self.request("tools/call", {"name": name, "arguments": arguments or {}})
        if result.get("isError"):
            raise RuntimeError(f"Tool {name} rejected the call: {result.get('content')}")
        if "structuredContent" in result:
            return result["structuredContent"]
        return json.loads(result["content"][0]["text"])

    def close(self):
        if not self.session:
            return
        request = urllib.request.Request(self.url, method="DELETE", headers=self.headers())
        with urllib.request.urlopen(request, timeout=10):
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--demo-account", action="store_true", help="Create and delete a temporary account")
    parser.add_argument("--output", type=Path, help="Save a token-free request/response transcript")
    args = parser.parse_args()
    token = os.environ.get("MEALCHECK_MCP_TOKEN") or os.environ.get("TOKEN")
    if not args.demo_account and not token:
        parser.error("Use --demo-account or set MEALCHECK_MCP_TOKEN to a logged-in user's JWT")
    base = args.base_url.rstrip("/")
    transcript = {"endpoint": base + "/mcp", "steps": [], "temporaryAccountDeleted": None}
    password, client = None, None
    try:
        if args.demo_account:
            captcha = rest(base, "/api/auth/captcha")
            match = re.fullmatch(r"(\d+)\s*\+\s*(\d+)\s*=\s*\?", captcha["question"])
            if not match:
                raise RuntimeError("Unexpected captcha format")
            temporary_password = "McpDemo-" + uuid.uuid4().hex
            auth = rest(base, "/api/auth/register", "POST", {
                "username": "mcp_demo_" + uuid.uuid4().hex[:16],
                "displayName": "Temporary MCP demo",
                "password": temporary_password,
                "captchaId": captcha["captchaId"],
                "captchaAnswer": str(int(match[1]) + int(match[2])),
            })
            token, password = auth["token"], temporary_password

        client = McpClient(base, token, transcript["steps"])
        initialized = client.initialize()
        tools = client.request("tools/list")["tools"]
        names = [tool["name"] for tool in tools]
        route = client.call("route_assistant_skill", {"question": "我想减脂，晚餐怎么搭配？"})
        meals = client.call("get_my_recent_meals")
        report = client.call("get_my_weekly_report")
        transcript["summary"] = {
            "server": initialized["serverInfo"],
            "protocolVersion": initialized["protocolVersion"],
            "tools": names,
            "selectedSkill": route["skill"],
            "mealCount": len(meals["meals"]),
            "weeklyMealCount": report["report"]["totalMeals"],
        }
        transcript["passed"] = True
    finally:
        if client:
            try:
                client.close()
            except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError):
                # Session cleanup does not prevent cleanup of the temporary account.
                pass
        if password:
            rest(base, "/api/profile/account", "DELETE", {"password": password}, token, expected=204)
            rest(base, "/api/meals", token=token, expected=401)
            transcript["temporaryAccountDeleted"] = True
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(transcript, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        **transcript["summary"],
        "passed": transcript["passed"],
        "temporaryAccountDeleted": transcript["temporaryAccountDeleted"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
