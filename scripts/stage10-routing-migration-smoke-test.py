#!/usr/bin/env python3
"""End-to-end smoke test for a hot-conversation route migration."""

import json
import secrets
import time
import urllib.error
import urllib.request
import uuid


BASE_URL = "http://127.0.0.1:18080"
ROUTING_SECRET = "local-routing-secret-change-me"


def call(method, path, body=None, token=None, routing_admin=False):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if token:
        headers["Authorization"] = "Bearer " + token
    if routing_admin:
        headers["X-IM-Routing-Secret"] = ROUTING_SECRET
    request = urllib.request.Request(BASE_URL + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, json.loads(raw) if raw else None


def uuid7():
    value = (int(time.time() * 1000) << 80) | (7 << 76)
    value |= secrets.randbits(12) << 64
    value |= 2 << 62
    value |= secrets.randbits(62)
    return str(uuid.UUID(int=value))


def register(label, stamp):
    status, value = call("POST", "/api/v1/auth/register", {
        "account": f"stage10-{label}-{stamp}",
        "password": "correct-password",
        "nickname": f"阶段十{label.upper()}",
        "deviceId": str(uuid.uuid4()),
    })
    assert status == 201, (status, value)
    return value


status, health = call("GET", "/actuator/health/readiness")
assert status == 200 and health["status"] == "UP", (status, health)

stamp = str(int(time.time() * 1000))
alice = register("a", stamp)
bob = register("b", stamp)
token_a, token_b = alice["accessToken"], bob["accessToken"]

status, friend_request = call("POST", "/api/v1/friend-requests", {
    "recipientId": bob["user"]["id"], "note": "stage10"
}, token_a)
assert status == 201, (status, friend_request)
status, accepted = call("POST", f"/api/v1/friend-requests/{friend_request['id']}/accept", {}, token_b)
assert status == 200, (status, accepted)

status, conversation = call("POST", "/api/v1/conversations/direct", {
    "peerUserId": bob["user"]["id"]
}, token_a)
assert status == 201, (status, conversation)
conversation_id = conversation["id"]

for number in range(3):
    status, sent = call("POST", "/api/v1/messages", {
        "conversationId": conversation_id,
        "clientMsgId": uuid7(),
        "type": "TEXT",
        "content": {"text": f"before-migration-{number}"},
        "attachmentIds": [],
    }, token_a)
    assert status == 201, (status, sent)

status, unauthorized = call("GET", f"/internal/v1/routing/routes/{conversation_id}")
assert status in (400, 401), (status, unauthorized)

status, original = call("GET", f"/internal/v1/routing/routes/{conversation_id}", routing_admin=True)
assert status == 200 and original["state"] == "ACTIVE", (status, original)
target_group = (original["tableGroup"] + 1) % 8

status, migration = call("POST", "/internal/v1/routing/migrations", {
    "scopeType": "CONVERSATION",
    "conversationId": conversation_id,
    "targetShard": original["shardId"],
    "targetTableGroup": target_group,
}, routing_admin=True)
assert status == 201 and migration["state"] == "COMPLETED", (status, migration)
assert migration["sourceEpoch"] + 1 == migration["targetEpoch"], migration
assert migration["checkpoint"]["messageCount"] == 3, migration
assert migration["checkpoint"]["memberCount"] == 2, migration

status, moved = call("GET", f"/internal/v1/routing/routes/{conversation_id}", routing_admin=True)
assert status == 200, (status, moved)
assert moved["scope"] == "CONVERSATION", moved
assert moved["tableGroup"] == target_group, moved
assert moved["routeEpoch"] == original["routeEpoch"] + 1, moved

status, history = call("GET", f"/api/v1/conversations/{conversation_id}/messages?limit=50", token=token_b)
assert status == 200 and len(history["items"]) == 3, (status, history)

status, sent_after = call("POST", "/api/v1/messages", {
    "conversationId": conversation_id,
    "clientMsgId": uuid7(),
    "type": "TEXT",
    "content": {"text": "after-migration"},
    "attachmentIds": [],
}, token_a)
assert status == 201 and int(sent_after["seq"]) == 4, (status, sent_after)

status, final_history = call("GET", f"/api/v1/conversations/{conversation_id}/messages?limit=50", token=token_b)
assert status == 200 and len(final_history["items"]) == 4, (status, final_history)

print("PASS: 1024-slot control-plane route resolved")
print(f"PASS: conversation migrated from group {original['tableGroup']} to {target_group}")
print(f"PASS: route epoch advanced from {original['routeEpoch']} to {moved['routeEpoch']}")
print("PASS: copied history is readable and writes continue on the target route")
