#!/usr/bin/env python3
import base64, json, os, socket, struct, time, urllib.error, urllib.parse, urllib.request, uuid

HTTP = "http://127.0.0.1:18080"
ORIGIN = "http://localhost:3000"

def call(method, path, body=None, token=None, headers=None):
    request_headers = {"Accept": "application/json", **(headers or {})}
    data = None
    if body is not None:
        request_headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if token: request_headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(HTTP + path, data=data, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        try: value = json.loads(raw) if raw else None
        except json.JSONDecodeError: value = raw.decode(errors="replace")
        return error.code, value

def uuid7():
    value = (int(time.time() * 1000) << 80) | (7 << 76) | (uuid.uuid4().int & ((1 << 76) - 1))
    value = (value & ~(3 << 62)) | (2 << 62)
    return str(uuid.UUID(int=value))

class WebSocket:
    def __init__(self, url):
        parsed = urllib.parse.urlparse(url)
        self.sock = socket.create_connection((parsed.hostname, parsed.port or 80), timeout=10)
        self.sock.settimeout(15)
        key = base64.b64encode(os.urandom(16)).decode()
        path = parsed.path + ("?" + parsed.query if parsed.query else "")
        request = (f"GET {path} HTTP/1.1\r\nHost: {parsed.hostname}:{parsed.port}\r\n"
                   f"Origin: {ORIGIN}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode()
        self.sock.sendall(request)
        response = b""
        while b"\r\n\r\n" not in response: response += self.sock.recv(4096)
        headers, self.buffer = response.split(b"\r\n\r\n", 1)
        self.status = int(headers.split(b" ", 2)[1])

    def send_json(self, value): self.send_frame(1, json.dumps(value, separators=(",", ":")).encode())
    def send_frame(self, opcode, payload):
        mask = os.urandom(4); length = len(payload); head = bytearray([0x80 | opcode])
        if length < 126: head.append(0x80 | length)
        elif length < 65536: head.extend([0x80 | 126]); head.extend(struct.pack("!H", length))
        else: head.extend([0x80 | 127]); head.extend(struct.pack("!Q", length))
        head.extend(mask); head.extend(bytes(value ^ mask[index % 4] for index, value in enumerate(payload)))
        self.sock.sendall(head)

    def recv_json(self):
        while True:
            first = self._read(2); opcode = first[0] & 0x0f; length = first[1] & 0x7f
            if length == 126: length = struct.unpack("!H", self._read(2))[0]
            elif length == 127: length = struct.unpack("!Q", self._read(8))[0]
            mask = self._read(4) if first[1] & 0x80 else None; payload = self._read(length)
            if mask: payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
            if opcode == 9: self.send_frame(10, payload); continue
            if opcode == 1: return json.loads(payload)
            if opcode == 8: raise ConnectionError("WebSocket closed")

    def receive(self, predicate, maximum=20):
        seen = []
        for _ in range(maximum):
            value = self.recv_json(); seen.append(value)
            if predicate(value): return value
        raise AssertionError(seen)

    def close(self):
        try: self.send_frame(8, struct.pack("!H", 1000)); self.sock.close()
        except OSError: pass

    def _read(self, count):
        value = self.buffer[:count]; self.buffer = self.buffer[count:]
        while len(value) < count:
            part = self.sock.recv(count - len(value))
            if not part: raise ConnectionError("WebSocket closed")
            value += part
        return value

stamp = str(int(time.time() * 1000))
def register(label):
    device = str(uuid.uuid4())
    status, value = call("POST", "/api/v1/auth/register", {
        "account": f"stage6-{label.lower()}-{stamp}", "password": "correct-password",
        "nickname": label, "deviceId": device})
    assert status == 201, (status, value)
    return value

def connect(token):
    status, ticket = call("POST", "/api/v1/ws/tickets", {}, token)
    assert status == 201, (status, ticket)
    ws = WebSocket(ticket["websocketUrl"]); assert ws.status == 101, ws.status
    ready = ws.recv_json(); assert ready["op"] == "READY", ready
    return ws, ready

owner = register("Owner"); member = register("Member")
status, friend_request = call("POST", "/api/v1/friend-requests", {
    "recipientId": member["user"]["id"], "note": "stage 6"}, owner["accessToken"])
assert status == 201, (status, friend_request)
status, accepted = call("POST", f"/api/v1/friend-requests/{friend_request['id']}/accept", {}, member["accessToken"])
assert status == 200, (status, accepted)
status, direct = call("POST", "/api/v1/conversations/direct", {
    "peerUserId": member["user"]["id"]}, owner["accessToken"])
assert status == 201, (status, direct)
conversation = direct["id"]
owner_ws, owner_ready = connect(owner["accessToken"])
member_ws, member_ready = connect(member["accessToken"])
time.sleep(0.5)

request_id = str(uuid.uuid4())
owner_ws.send_json({"v": 1, "op": "SEND", "requestId": request_id, "data": {
    "conversationId": conversation, "clientMsgId": uuid7(), "type": "TEXT",
    "content": {"text": "stage 6 realtime"}, "attachmentIds": []}})
ack = owner_ws.receive(lambda x: x.get("op") == "SEND_ACK" and x.get("requestId") == request_id)
sequence = int(ack["data"]["seq"]); message_id = ack["data"]["messageId"]
owner_message = owner_ws.receive(lambda x: x.get("op") == "MESSAGE" and x["data"].get("messageId") == message_id)
member_message = member_ws.receive(lambda x: x.get("op") == "MESSAGE" and x["data"].get("messageId") == message_id)
assert owner_message["data"]["seq"] == str(sequence) and member_message["data"]["seq"] == str(sequence)

# Both devices are already online when a new group is created. Its first message must still
# reach the invited member even though it was absent from the member's login-time directory.
status, group = call("POST", "/api/v1/conversations/group", {
    "title": "Realtime new group", "memberIds": [member["user"]["id"]]}, owner["accessToken"])
assert status == 201, (status, group)
group_request = str(uuid.uuid4())
owner_ws.send_json({"v": 1, "op": "SEND", "requestId": group_request, "data": {
    "conversationId": group["id"], "clientMsgId": uuid7(), "type": "TEXT",
    "content": {"text": "new group realtime"}, "attachmentIds": []}})
group_ack = owner_ws.receive(lambda x: x.get("op") == "SEND_ACK" and x.get("requestId") == group_request)
group_message_id = group_ack["data"]["messageId"]
owner_ws.receive(lambda x: x.get("op") == "MESSAGE" and x["data"].get("messageId") == group_message_id)
member_ws.receive(lambda x: x.get("op") == "MESSAGE" and x["data"].get("messageId") == group_message_id)

read_request = str(uuid.uuid4())
member_ws.send_json({"v": 1, "op": "READ", "requestId": read_request,
                      "data": {"conversationId": conversation, "seq": str(sequence)}})
read_ack = member_ws.receive(lambda x: x.get("op") == "READ_UPDATE" and x.get("requestId") == read_request)
peer_read = owner_ws.receive(lambda x: x.get("op") == "READ_UPDATE"
                              and x["data"].get("userId") == member["user"]["id"])
assert int(read_ack["data"]["readSeq"]) == sequence and int(peer_read["data"]["readSeq"]) == sequence

member_ws.close(); time.sleep(0.3)
second_request = str(uuid.uuid4())
owner_ws.send_json({"v": 1, "op": "SEND", "requestId": second_request, "data": {
    "conversationId": conversation, "clientMsgId": uuid7(), "type": "TEXT",
    "content": {"text": "offline delta"}, "attachmentIds": []}})
second_ack = owner_ws.receive(lambda x: x.get("op") == "SEND_ACK" and x.get("requestId") == second_request)
second_sequence = int(second_ack["data"]["seq"]); second_id = second_ack["data"]["messageId"]

status, sync = call("POST", "/api/v1/sync", {
    "conversations": [{"conversationId": conversation, "afterSeq": sequence}],
    "limitPerConversation": 20}, member["accessToken"])
assert status == 200, (status, sync)
items = sync["conversations"][0]["page"]["items"]
assert any(item["id"] == second_id for item in items), sync

status, latest = call("PUT", f"/api/v1/conversations/{conversation}/read",
                      {"seq": second_sequence}, member["accessToken"])
assert status == 200 and latest["readSeq"] == second_sequence, (status, latest)
status, older = call("PUT", f"/api/v1/conversations/{conversation}/read",
                     {"seq": sequence}, member["accessToken"])
assert status == 200 and older["readSeq"] == second_sequence, (status, older)

dispatch_marker = str(uuid.uuid4())
status, dispatch = call("POST", "/internal/v1/gateway/dispatch", {
    "connectionIds": [owner_ready["data"]["connectionId"]], "operation": "SERVER_TEST",
    "data": {"marker": dispatch_marker}}, headers={"X-IM-Dispatch-Secret": "local-dispatch-secret-change-me"})
assert status == 200 and dispatch["accepted"] == 1, (status, dispatch)
server_test = owner_ws.receive(lambda x: x.get("op") == "SERVER_TEST")
assert server_test["data"]["marker"] == dispatch_marker, server_test

status, _ = call("POST", "/internal/v1/gateway/dispatch", {
    "connectionIds": [owner_ready["data"]["connectionId"]], "operation": "PING", "data": {}})
assert status == 401, status

owner_ws.close()
print("PASS: Redis leases expose both online devices to the delivery worker")
print("PASS: RocketMQ MESSAGE_CREATED events push MESSAGE to both WebSocket clients")
print("PASS: a group created after both clients connect pushes its first message to the invited member")
print("PASS: READ persists a monotonic cursor and pushes direct-chat READ_UPDATE")
print("PASS: POST /api/v1/sync returns messages missed while a device was offline")
print("PASS: internal gateway Dispatch delivers batches and rejects missing shared secrets")
