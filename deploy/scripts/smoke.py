"""Exercise a running release using a temporary account; never invokes AI."""
import argparse
import json
import re
import urllib.error
import urllib.request
import uuid


def check(base, path, method="GET", body=None, token=None, expected=200, extra_headers=None):
    headers = dict(extra_headers or {})
    if token: headers["Authorization"] = "Bearer " + token
    if body is not None: headers["Content-Type"] = "application/json"
    request = urllib.request.Request(base.rstrip("/") + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    if status != expected:
        raise RuntimeError(f"{method} {path}: expected {expected}, received {status}")
    if not raw: return None
    if path == "/":
        if b"<html" not in raw.lower(): raise RuntimeError("Frontend did not return HTML")
        return None
    return json.loads(raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    args = parser.parse_args()
    base = args.base_url
    check(base, "/")
    check(base, "/api/meals", expected=401)
    captcha = check(base, "/api/auth/captcha")
    match = re.fullmatch(r"(\d+)\s*\+\s*(\d+)\s*=\s*\?", captcha["question"])
    if not match: raise RuntimeError("Unexpected captcha format")
    password = "Smoke-" + uuid.uuid4().hex
    auth = check(base, "/api/auth/register", "POST", {
        "username": "smoke_" + uuid.uuid4().hex[:16], "displayName": "Release smoke test", "password": password,
        "captchaId": captcha["captchaId"], "captchaAnswer": str(int(match[1]) + int(match[2]))
    })
    token = auth["token"]
    try:
        records = check(base, "/api/meals?page=0&size=10", token=token)
        if records["totalElements"] != 0: raise RuntimeError("Fresh account contains another account's meals")
        report = check(base, "/api/reports/weekly/latest", token=token)
        if report["totalMeals"] != 0 or not report.get("periodStart"): raise RuntimeError("Invalid empty weekly report")
        check(base, "/api/admin/meal-corrections", token=token, expected=403)
        check(base, "/api/meals/analysis-requests/unknown-request-key", token=token, expected=404)
    finally:
        check(base, "/api/profile/account", "DELETE", {"password": password}, token, expected=204)
    check(base, "/api/meals", token=token, expected=401)
    # Run through the actual frontend proxy. Rotating client-supplied
    # Forwarded/X-Forwarded headers must not create fresh rate-limit buckets.
    # X-Real-IP is trusted only from the documented host TLS proxy channel.
    limited = False
    for attempt in range(30):
        headers = {"Forwarded": f"for=198.51.100.{attempt+1};proto=https;host=evil.invalid",
                   "X-Forwarded-For": f"198.51.100.{attempt+1}",
                   "X-Forwarded-Host": "evil.invalid", "X-Forwarded-Prefix": "/spoof"}
        try:
            check(base, "/api/auth/captcha", extra_headers=headers)
        except RuntimeError as error:
            if "received 429" not in str(error): raise
            limited = True
            break
    if not limited: raise RuntimeError("Forged forwarding headers bypassed the proxy captcha rate limit")
    print(json.dumps({"passed": True, "accountCleanedUp": True, "proxyRateLimitVerified": True, "aiCalls": 0}))


if __name__ == "__main__": main()
