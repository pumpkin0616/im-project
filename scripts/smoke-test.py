#!/usr/bin/env python3
"""Local HTTP smoke test using only the Python standard library."""

import json
import secrets
import time
import urllib.error
import urllib.request


BASE_URL = "http://127.0.0.1:18080"
USER_ID = "0199f120-0000-7000-8000-000000000001"
CONVERSATION_ID = "bd258e60-98b5-3334-9859-e0112b4562e9"


def uuid7() -> str:
    timestamp_ms = int(time.time() * 1000) & ((1 << 48) - 1)
    value = timestamp_ms << 80
    value |= 0x7 << 76
    value |= secrets.randbits(12) << 64
    value |= 0b10 << 62
    value |= secrets.randbits(62)
    text = f"{value:032x}"
    return f"{text[:8]}-{text[8:12]}-{text[12:16]}-{text[16:20]}-{text[20:]}"


def request(path: str, body: dict | None = None) -> tuple[int, dict]:
    headers = {"Accept": "application/json"}
    data = None
    method = "GET"
    if body is not None:
        method = "POST"
        headers.update({"Content-Type": "application/json", "X-User-Id": USER_ID})
        data = json.dumps(body).encode()
    req = urllib.request.Request(BASE_URL + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


health_status, health = request("/actuator/health/readiness")
assert health_status == 200 and health["status"] == "UP", (health_status, health)

client_message_id = uuid7()
message = {
    "conversationId": CONVERSATION_ID,
    "clientMsgId": client_message_id,
    "type": "TEXT",
    "content": {"text": f"smoke-test-{client_message_id}"},
    "attachmentIds": [],
}

first_status, first = request("/api/v1/messages", message)
assert first_status == 201 and first["replayed"] is False, (first_status, first)

replay_status, replay = request("/api/v1/messages", message)
assert replay_status == 201 and replay["replayed"] is True, (replay_status, replay)
assert replay["messageId"] == first["messageId"] and replay["seq"] == first["seq"]

conflicting = {**message, "content": {"text": "conflicting-content"}}
conflict_status, conflict = request("/api/v1/messages", conflicting)
assert conflict_status == 409 and conflict["code"] == "IDEMPOTENCY_CONFLICT", (
    conflict_status,
    conflict,
)

print("PASS: service is ready")
print(f"PASS: message created, messageId={first['messageId']}, seq={first['seq']}")
print("PASS: identical retry returned the original message")
print("PASS: conflicting retry was rejected with HTTP 409")
