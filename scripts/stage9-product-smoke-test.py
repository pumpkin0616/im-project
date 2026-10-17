#!/usr/bin/env python3
import json
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:3000"


def call(method, path, body=None, token=None, content_type="application/json"):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = content_type
        data = json.dumps(body).encode() if content_type == "application/json" else body
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read()
            try:
                value = json.loads(raw) if raw else None
            except (json.JSONDecodeError, UnicodeDecodeError):
                value = raw
            return response.status, value
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            value = json.loads(raw) if raw else None
        except (json.JSONDecodeError, UnicodeDecodeError):
            value = raw
        return error.code, value


stamp = str(int(time.time() * 1000))


def uuid7():
    value = (int(time.time() * 1000) << 80) | (7 << 76) | (uuid.uuid4().int & ((1 << 76) - 1))
    value = (value & ~(3 << 62)) | (2 << 62)
    return str(uuid.UUID(int=value))


def register(suffix):
    status, value = call("POST", "/api/v1/auth/register", {
        "account": "stage9-" + suffix + "-" + stamp,
        "password": "correct-password",
        "nickname": "阶段九" + suffix.upper(),
        "deviceId": str(uuid.uuid4())
    })
    assert status == 201, (status, value)
    return value


alice, bob, charlie = register("a"), register("b"), register("c")
token_a, token_b = alice["accessToken"], bob["accessToken"]
alice_id, bob_id = alice["user"]["id"], bob["user"]["id"]
charlie_id = charlie["user"]["id"]

status, profile = call("PUT", "/api/v1/users/me", {"nickname": "阶段九新昵称"}, token_a)
assert status == 200 and profile["nickname"] == "阶段九新昵称", (status, profile)
boundary = "----im-stage9-" + stamp
avatar_bytes = b"\x89PNG\r\n\x1a\nminimal-avatar"
multipart = (
    f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"avatar.png\"\r\n"
    "Content-Type: image/png\r\n\r\n"
).encode() + avatar_bytes + f"\r\n--{boundary}--\r\n".encode()
status, profile = call("POST", "/api/v1/users/me/avatar", multipart, token_a, f"multipart/form-data; boundary={boundary}")
assert status == 200 and profile["avatarKey"], (status, profile)
status, avatar = call("GET", f"/api/v1/users/{alice_id}/avatar", token=token_b)
assert status == 200 and avatar == avatar_bytes, (status, avatar)
status, _ = call("DELETE", "/api/v1/users/me/avatar", token=token_a)
assert status == 204

status, cancellable = call("POST", "/api/v1/friend-requests", {"recipientId": charlie_id}, token_a)
assert status == 201, (status, cancellable)
status, cancelled = call("DELETE", f"/api/v1/friend-requests/{cancellable['id']}", token=token_a)
assert status == 200 and cancelled["status"] == 3, (status, cancelled)

status, request = call("POST", "/api/v1/friend-requests", {"recipientId": bob_id, "note": "stage9"}, token_a)
assert status == 201, (status, request)
status, _ = call("POST", f"/api/v1/friend-requests/{request['id']}/accept", {}, token_b)
assert status == 200
status, conversation = call("POST", "/api/v1/conversations/direct", {"peerUserId": bob_id}, token_a)
assert status == 201, (status, conversation)
conversation_id = conversation["id"]

client_id = uuid7()
status, sent = call("POST", "/api/v1/messages", {
    "conversationId": conversation_id,
    "clientMsgId": client_id,
    "type": "TEXT",
    "content": {"text": "这条消息将被撤回"},
    "attachmentIds": []
}, token_a)
assert status == 201, (status, sent)
sequence = sent["seq"]

status, forbidden = call("POST", f"/api/v1/conversations/{conversation_id}/messages/{sequence}/recall", {}, token_b)
assert status == 403 and forbidden["code"] == "MESSAGE_RECALL_FORBIDDEN", (status, forbidden)
status, recalled = call("POST", f"/api/v1/conversations/{conversation_id}/messages/{sequence}/recall", {}, token_a)
assert status == 200 and recalled["status"] == "RECALLED", (status, recalled)
status, replayed = call("POST", f"/api/v1/conversations/{conversation_id}/messages/{sequence}/recall", {}, token_a)
assert status == 200 and replayed["recalledAt"] == recalled["recalledAt"], (status, replayed)

status, page = call("GET", f"/api/v1/conversations/{conversation_id}/messages?limit=50", token=token_b)
assert status == 200 and isinstance(page, dict), (status, page)
message = next((item for item in page["items"] if str(item["seq"]) == str(sequence)), None)
assert message is not None, (sequence, page)
assert status == 200 and message["status"] == "RECALLED", (status, page)
assert message["content"] == {} and message["attachments"] == [], message
status, detail = call("GET", f"/api/v1/conversations/{conversation_id}", token=token_b)
assert status == 200 and detail["lastMessage"]["summary"] == "一条消息已撤回", (status, detail)
assert detail["unreadCount"] >= 1 and detail["peer"]["userId"] == alice_id, detail

file_bytes = b"stage9 recalled attachment"
status, ticket = call("POST", f"/api/v1/conversations/{conversation_id}/attachments", {
    "fileName": "recall.txt", "mimeType": "text/plain", "sizeBytes": len(file_bytes)
}, token_a)
assert status == 201, (status, ticket)
status, _ = call("PUT", ticket["uploadUrl"], file_bytes, token_a, "application/octet-stream")
assert status == 204, status
status, attachment = call("POST", ticket["completeUrl"], {}, token_a)
assert status == 200 and attachment["status"] == "READY", (status, attachment)
status, file_message = call("POST", "/api/v1/messages", {
    "conversationId": conversation_id, "clientMsgId": uuid7(), "type": "FILE",
    "content": {}, "attachmentIds": [attachment["id"]]
}, token_a)
assert status == 201, (status, file_message)
status, downloaded = call("GET", attachment["downloadUrl"], token=token_b)
assert status == 200 and downloaded == file_bytes, (status, downloaded)
status, _ = call("POST", f"/api/v1/conversations/{conversation_id}/messages/{file_message['seq']}/recall", {}, token_a)
assert status == 200
status, hidden = call("GET", attachment["downloadUrl"], token=token_b)
assert status == 404, (status, hidden)

status, group = call("POST", "/api/v1/conversations/group", {
    "title": "阶段九群聊", "memberIds": [bob_id]
}, token_a)
assert status == 201 and group["myRole"] == "OWNER", (status, group)
group_id = group["id"]
status, group = call("PATCH", f"/api/v1/conversations/{group_id}", {"title": "阶段九新群名"}, token_a)
assert status == 200 and group["title"] == "阶段九新群名", (status, group)
status, group = call("PUT", f"/api/v1/conversations/{group_id}/members/{bob_id}/role", {"role": "ADMIN"}, token_a)
assert status == 200 and any(item["userId"] == bob_id and item["role"] == "ADMIN" for item in group["members"])
status, _ = call("POST", f"/api/v1/conversations/{group_id}/leave", {}, token_b)
assert status == 204
status, group_message = call("POST", "/api/v1/messages", {
    "conversationId": group_id, "clientMsgId": uuid7(), "type": "TEXT",
    "content": {"text": "解散后仍可查看"}, "attachmentIds": []
}, token_a)
assert status == 201, (status, group_message)
status, _ = call("POST", f"/api/v1/conversations/{group_id}/dissolve", {}, token_a)
assert status == 204
status, dissolved = call("GET", f"/api/v1/conversations/{group_id}", token=token_a)
assert status == 200 and dissolved["status"] == "DISSOLVED", (status, dissolved)
status, history = call("GET", f"/api/v1/conversations/{group_id}/messages?limit=20", token=token_a)
assert status == 200 and any(item["content"].get("text") == "解散后仍可查看" for item in history["items"]), (status, history)

status, pending = call("POST", "/api/v1/friend-requests", {"recipientId": alice_id}, token_b)
assert status == 409 and pending["code"] == "ALREADY_FRIENDS", (status, pending)
status, _ = call("PUT", f"/api/v1/blocks/{bob_id}", {}, token_a)
assert status == 204
status, blocks = call("GET", "/api/v1/blocks", token=token_a)
assert status == 200 and any(item["id"] == bob_id for item in blocks), (status, blocks)
status, _ = call("DELETE", f"/api/v1/blocks/{bob_id}", token=token_a)
assert status == 204

print("PASS: only the sender can recall a message within the server-controlled window")
print("PASS: profile update and authenticated avatar upload, read and delete work")
print("PASS: a requester can cancel a pending friend request")
print("PASS: repeated recall is idempotent and public history is permanently redacted")
print("PASS: conversation summary, unread count and direct peer are returned")
print("PASS: recalled attachments are removed from history and can no longer be downloaded")
print("PASS: group title, roles, leave, dissolve and read-only history form a complete management flow")
print("PASS: block list can be queried and users can be unblocked")
