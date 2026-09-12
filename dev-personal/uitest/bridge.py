#!/usr/bin/env python3
"""The host side of the iOS UI tests.

Two things a test running inside the simulator cannot do for itself, served
over loopback on 18099:

  GET /faceid/match   tell the simulator the face matched
  GET /cleanup        soft-delete anything the capture test left behind

Neither stubs the app or the API. The first is the same notification the
Simulator's own Features -> Face ID -> Matching Face menu item posts, and there
is no XCUITest API for biometry. The second is a real authenticated DELETE
through the product's own endpoint, so it sets `deleted_at` the way the web
client would and passes every permission check on the way.

This used to serve the one-time code as well, by tailing the backend's log, and
before that by writing it into the simulator's device-wide /tmp — which a UI
test cannot read, being itself a sandboxed app with a /tmp of its own. Neither
is needed: when the server reports that no SMS provider is configured, the app
says the code on screen, so the test reads it from there. Two layers of host
plumbing removed by looking at what the product already tells the user.
"""
from __future__ import annotations

import http.server
import json
import re
import subprocess
import threading
import urllib.error
import urllib.request

PORT = 18099
API = "http://localhost:18080"
CONTAINER = "almira-personal-app"
DEVICE = "booted"
# What the capture test names its holding. Anything starting with this is that
# test's own litter and nothing else.
TEST_HOLDING_PREFIX = "Stage three test"
CLEANUP_AS = "+919889190735"

OTP_IN_LOG = re.compile(r"DEV OTP for \S+ : (\d{6})")


def api(method: str, path: str, token: str | None = None, body: dict | None = None):
    request = urllib.request.Request(f"{API}{path}", method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(request, data, timeout=20) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as failure:
        return failure.code, failure.read().decode(errors="replace")


def sign_in(phone: str) -> str | None:
    """A real sign-in, for the cleanup that needs a token of its own.

    Takes the code from the backend's log rather than off a screen, which is
    fine here: this is host-side housekeeping, not the path under test.
    """
    api("POST", "/api/v1/auth/otp/request", body={"phone": phone})
    code = None
    for _ in range(20):
        threading.Event().wait(0.5)
        logs = subprocess.run(
            ["docker", "logs", "--since", "60s", CONTAINER],
            capture_output=True, text=True, timeout=30,
        )
        found = OTP_IN_LOG.findall(logs.stdout + logs.stderr)
        if found:
            code = found[-1]
            break
    if not code:
        return None

    status, payload = api("POST", "/api/v1/auth/otp/verify", body={"phone": phone, "code": code})
    if status >= 300 or not isinstance(payload, dict):
        print(f"cleanup sign-in failed: {status} {payload}", flush=True)
        return None
    return payload.get("accessToken")


def cleanup() -> str:
    """Soft-delete the capture test's holding, as a real user."""
    token = sign_in(CLEANUP_AS)
    if not token:
        return "cleanup: could not sign in"

    status, households = api("GET", "/api/v1/households", token)
    if status >= 300 or not households:
        return f"cleanup: no households ({status})"
    household = households[0]["id"]

    status, holdings = api("GET", f"/api/v1/households/{household}/investments", token)
    if status >= 300 or holdings is None:
        return f"cleanup: could not list holdings ({status})"

    removed = []
    for holding in holdings:
        if str(holding.get("title", "")).startswith(TEST_HOLDING_PREFIX):
            code, _ = api(
                "DELETE", f"/api/v1/households/{household}/investments/{holding['id']}", token
            )
            removed.append(f"{holding['title']} -> {code}")
    return "cleanup: " + ("; ".join(removed) if removed else "nothing to remove")


class Handler(http.server.BaseHTTPRequestHandler):
    def reply(self, body: str = "", status: int = 200) -> None:
        payload = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self) -> None:
        if self.path.startswith("/faceid/match"):
            subprocess.run(
                ["xcrun", "simctl", "spawn", DEVICE, "notifyutil",
                 "-p", "com.apple.BiometricKit_Sim.pearl.match"],
                capture_output=True, timeout=20,
            )
            return self.reply()

        if self.path.startswith("/cleanup"):
            result = cleanup()
            print(result, flush=True)
            return self.reply(result)

        if self.path.startswith("/health"):
            return self.reply("ok")

        self.reply("", 404)

    def log_message(self, *args) -> None:
        pass


if __name__ == "__main__":
    print(f"bridge listening on 127.0.0.1:{PORT}", flush=True)
    http.server.HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
