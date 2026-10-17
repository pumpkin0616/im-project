#!/usr/bin/env python3
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "http://127.0.0.1:3000"


def call(method, path, body=None, token=None):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, json.loads(raw) if raw else None


stamp = str(int(time.time() * 1000))
account_a = "contact-a-" + stamp
account_b = "contact-b-" + stamp
status, alice = call("POST", "/api/v1/auth/register", {
    "account": account_a, "password": "correct-password", "nickname": "联系人甲",
    "deviceId": str(uuid.uuid4())
})
assert status == 201, (status, alice)
status, bob = call("POST", "/api/v1/auth/register", {
    "account": account_b, "password": "correct-password", "nickname": "联系人乙",
    "deviceId": str(uuid.uuid4())
})
assert status == 201, (status, bob)
token_a = alice["accessToken"]
token_b = bob["accessToken"]
alice_id = alice["user"]["id"]
bob_id = bob["user"]["id"]

query = urllib.parse.quote(account_b.upper())
status, results = call("GET", f"/api/v1/users/search?q={query}&limit=20", token=token_a)
assert status == 200 and results[0]["account"] == account_b, (status, results)
assert results[0]["relationship"] == "NONE", results[0]

status, friend_request = call("POST", "/api/v1/friend-requests", {
    "recipientId": bob_id, "note": "我是联系人甲"
}, token_a)
assert status == 201 and friend_request["requesterAccount"] == account_a, (status, friend_request)

status, results = call("GET", f"/api/v1/users/search?q={query}", token=token_a)
assert status == 200 and results[0]["relationship"] == "OUTGOING_PENDING", (status, results)
status, incoming = call("GET", "/api/v1/friend-requests?direction=incoming", token=token_b)
pending = [item for item in incoming if item["id"] == friend_request["id"] and item["status"] == 0]
assert status == 200 and pending and pending[0]["requesterAccount"] == account_a, (status, incoming)

status, accepted = call("POST", f"/api/v1/friend-requests/{friend_request['id']}/accept", {}, token_b)
assert status == 200 and accepted["status"] == 1, (status, accepted)
status, friends = call("GET", "/api/v1/friends", token=token_a)
assert status == 200 and any(friend["id"] == bob_id and friend["account"] == account_b for friend in friends), (status, friends)

status, direct_one = call("POST", "/api/v1/conversations/direct", {"peerUserId": bob_id}, token=token_a)
assert status in (200, 201), (status, direct_one)
status, direct_two = call("POST", "/api/v1/conversations/direct", {"peerUserId": alice_id}, token=token_b)
assert status in (200, 201) and direct_two["id"] == direct_one["id"], (status, direct_one, direct_two)

status, _ = call("DELETE", f"/api/v1/friends/{bob_id}", token=token_a)
assert status == 204, status
status, friends = call("GET", "/api/v1/friends", token=token_b)
assert status == 200 and all(friend["id"] != alice_id for friend in friends), (status, friends)

status, _ = call("PUT", f"/api/v1/blocks/{bob_id}", {}, token=token_a)
assert status == 204, status
status, blocked = call("POST", "/api/v1/friend-requests", {"recipientId": alice_id}, token=token_b)
assert status == 422 and blocked["code"] == "USER_BLOCKED", (status, blocked)

print("PASS: IM ID search prioritizes the exact account and exposes relationship state")
print("PASS: request badge data, account display, accept flow and friend list are valid")
print("PASS: both users open the same unique direct conversation")
print("PASS: deleting a friend is bilateral and blocking prevents new requests")
