#!/usr/bin/env python3
"""End-to-end archive, cold-read, partition and cleanup verification."""

import datetime
import json
import secrets
import subprocess
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:18080"
SECRET = "local-lifecycle-secret-change-me"
ROUTING_SECRET = "local-routing-secret-change-me"


def call(method, path, body=None, token=None, lifecycle=False, routing=False):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if token:
        headers["Authorization"] = "Bearer " + token
    if lifecycle:
        headers["X-IM-Lifecycle-Secret"] = SECRET
    if routing:
        headers["X-IM-Routing-Secret"] = ROUTING_SECRET
    request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
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
        "account": f"stage11-{label}-{stamp}", "password": "correct-password",
        "nickname": f"阶段十一{label.upper()}", "deviceId": str(uuid.uuid4()),
    })
    assert status == 201, (status, value)
    return value


def mysql(sql):
    result = subprocess.run([
        "docker", "compose", "exec", "-T", "mysql", "mysql", "-uroot", "-plocal-root",
        "--skip-column-names", "-e", sql,
    ], check=True, text=True, capture_output=True)
    return result.stdout.strip()


stamp = str(int(time.time() * 1000))
alice, bob = register("a", stamp), register("b", stamp)
token_a, token_b = alice["accessToken"], bob["accessToken"]
status, request = call("POST", "/api/v1/friend-requests", {"recipientId": bob["user"]["id"]}, token_a)
assert status == 201, (status, request)
status, _ = call("POST", f"/api/v1/friend-requests/{request['id']}/accept", {}, token_b)
assert status == 200
status, conversation = call("POST", "/api/v1/conversations/direct", {"peerUserId": bob["user"]["id"]}, token_a)
assert status == 201, (status, conversation)
conversation_id = conversation["id"]

for number in range(3):
    status, sent = call("POST", "/api/v1/messages", {
        "conversationId": conversation_id, "clientMsgId": uuid7(), "type": "TEXT",
        "content": {"text": f"archive-message-{number}"}, "attachmentIds": [],
    }, token_a)
    assert status == 201, (status, sent)

status, recalled = call("POST", f"/api/v1/conversations/{conversation_id}/messages/3/recall", {}, token_a)
assert status == 200 and recalled["status"] == "RECALLED", (status, recalled)
status, orphan = call("POST", f"/api/v1/conversations/{conversation_id}/attachments", {
    "fileName": "expired.tmp", "mimeType": "application/octet-stream", "sizeBytes": 4,
}, token_a)
assert status == 201, (status, orphan)

status, route = call("GET", f"/internal/v1/routing/routes/{conversation_id}", routing=True)
assert status == 200, (status, route)
group, slot = route["tableGroup"], route["slotId"]
suffix = f"{group:02d}"
archive_day = (datetime.datetime.now(datetime.timezone.utc).date() - datetime.timedelta(days=1)).isoformat()
binary_id = f"UUID_TO_BIN('{conversation_id}')"
mysql(
    f"UPDATE im_message.messages_{suffix} SET message_day='{archive_day}',created_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 10 DAY) "
    f"WHERE conversation_id={binary_id}; "
    f"UPDATE im_message.outbox_{suffix} SET message_day='{archive_day}',status=2,published_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 10 DAY),"
    f"lease_owner=NULL,lease_until=NULL WHERE conversation_id={binary_id}; "
    f"UPDATE im_message.attachments_{suffix} SET status=4,created_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 10 DAY),"
    f"expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 9 DAY) WHERE id=UUID_TO_BIN('{orphan['id']}');"
)

status, unauthorized = call("GET", "/internal/v1/lifecycle/status")
assert status == 401, (status, unauthorized)
status, batch = call("POST", "/internal/v1/lifecycle/archives", {
    "tableGroup": group, "slotId": slot, "messageDay": archive_day,
}, lifecycle=True)
assert status == 201 and batch["state"] == "HOT_DROPPED", (status, batch)
assert int(batch["rowCount"]) == 3 and len(batch["manifestSha256"]) == 64, batch

hot_count = mysql(f"SELECT COUNT(*) FROM im_message.messages_{suffix} WHERE conversation_id={binary_id};")
assert hot_count == "0", hot_count
status, history = call("GET", f"/api/v1/conversations/{conversation_id}/messages?limit=50", token=token_b)
assert status == 200 and len(history["items"]) == 3, (status, history)
assert history["items"][0]["content"]["text"] == "archive-message-0", history
assert history["items"][1]["content"]["text"] == "archive-message-1", history
assert history["items"][2]["status"] == "RECALLED" and history["items"][2]["content"] == {}, history
status, single = call("GET", f"/api/v1/conversations/{conversation_id}/messages/2", token=token_b)
assert status == 200 and single["content"]["text"] == "archive-message-1", (status, single)

target_group = (group + 1) % 8
status, migration = call("POST", "/internal/v1/routing/migrations", {
    "scopeType": "CONVERSATION", "conversationId": conversation_id,
    "targetShard": route["shardId"], "targetTableGroup": target_group,
}, routing=True)
assert status == 201 and migration["state"] == "COMPLETED", (status, migration)
assert migration["checkpoint"]["archiveSegmentCount"] >= 1, migration
status, moved_history = call("GET", f"/api/v1/conversations/{conversation_id}/messages?limit=50", token=token_b)
assert status == 200 and len(moved_history["items"]) == 3, (status, moved_history)

status, _ = call("POST", "/internal/v1/lifecycle/partitions/ensure", lifecycle=True)
assert status == 204, status
status, cleanup = call("POST", "/internal/v1/lifecycle/cleanup", lifecycle=True)
assert status == 200 and cleanup["deleted"]["outbox"] >= 3, (status, cleanup)
assert cleanup["deleted"]["attachments"] >= 1, cleanup

status, lifecycle_status = call("GET", "/internal/v1/lifecycle/status", lifecycle=True)
assert status == 200 and lifecycle_status["failedRuns"] == 0, (status, lifecycle_status)

print("PASS: verified Parquet archive was published before hot rows were deleted")
print("PASS: history and single-message APIs read transparently from cold storage")
print("PASS: archived recalled messages remain permanently redacted")
print("PASS: cold history remains readable after a conversation route migration")
print("PASS: lifecycle endpoints reject missing administration credentials")
print("PASS: future partitions were ensured and expired Outbox rows were cleaned")
print("PASS: expired unbound attachment metadata was cleaned")
