#!/usr/bin/env python3
import base64, hashlib, json, os, socket, struct, time, urllib.error, urllib.parse, urllib.request, uuid

HTTP="http://127.0.0.1:18080"
ORIGIN="http://localhost:3000"

def call(method,path,body=None,token=None):
    headers={"Accept":"application/json"}; data=None
    if body is not None: headers["Content-Type"]="application/json"; data=json.dumps(body).encode()
    if token: headers["Authorization"]="Bearer "+token
    request=urllib.request.Request(HTTP+path,data=data,headers=headers,method=method)
    try:
        with urllib.request.urlopen(request,timeout=15) as response:
            raw=response.read(); return response.status,json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw=error.read();
        try: value=json.loads(raw) if raw else None
        except json.JSONDecodeError: value=raw.decode(errors="replace")
        return error.code,value

def uuid7():
    value=(int(time.time()*1000)<<80)|(7<<76)|(uuid.uuid4().int&((1<<76)-1))
    value=(value&~(3<<62))|(2<<62)
    return str(uuid.UUID(int=value))

class WebSocket:
    def __init__(self,url,origin=ORIGIN):
        parsed=urllib.parse.urlparse(url)
        self.sock=socket.create_connection((parsed.hostname,parsed.port or 80),timeout=10)
        self.sock.settimeout(10)
        key=base64.b64encode(os.urandom(16)).decode()
        path=parsed.path+("?"+parsed.query if parsed.query else "")
        request=(f"GET {path} HTTP/1.1\r\nHost: {parsed.hostname}:{parsed.port}\r\n"
                 f"Origin: {origin}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                 f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode()
        self.sock.sendall(request)
        response=b""
        while b"\r\n\r\n" not in response: response+=self.sock.recv(4096)
        headers,self.buffer=response.split(b"\r\n\r\n",1)
        self.status=int(headers.split(b" ",2)[1])
        if self.status!=101: self.sock.close()

    def send_json(self,value): self.send_frame(1,json.dumps(value,separators=(",",":")).encode())
    def send_frame(self,opcode,payload):
        mask=os.urandom(4); length=len(payload); head=bytearray([0x80|opcode])
        if length<126: head.append(0x80|length)
        elif length<65536: head.extend([0x80|126]); head.extend(struct.pack("!H",length))
        else: head.extend([0x80|127]); head.extend(struct.pack("!Q",length))
        head.extend(mask); head.extend(bytes(value^mask[index%4] for index,value in enumerate(payload)))
        self.sock.sendall(head)

    def recv_frame(self):
        first=self._read(2); opcode=first[0]&0x0f; length=first[1]&0x7f
        if length==126: length=struct.unpack("!H",self._read(2))[0]
        elif length==127: length=struct.unpack("!Q",self._read(8))[0]
        masked=bool(first[1]&0x80); mask=self._read(4) if masked else None; payload=self._read(length)
        if mask: payload=bytes(value^mask[index%4] for index,value in enumerate(payload))
        return opcode,payload

    def recv_json(self):
        while True:
            opcode,payload=self.recv_frame()
            if opcode==9: self.send_frame(10,payload); continue
            if opcode==1: return json.loads(payload)
            if opcode==8: return {"op":"CLOSE","code":struct.unpack("!H",payload[:2])[0] if len(payload)>=2 else 1005}

    def close(self):
        try: self.send_frame(8,struct.pack("!H",1000)); self.sock.close()
        except OSError: pass
    def _read(self,count):
        value=self.buffer[:count]; self.buffer=self.buffer[count:]
        while len(value)<count:
            part=self.sock.recv(count-len(value))
            if not part: raise ConnectionError("WebSocket closed")
            value+=part
        return value

stamp=str(int(time.time()*1000)); device=str(uuid.uuid4())
def register(label,device_id):
    status,value=call("POST","/api/v1/auth/register",{
        "account":f"stage5-{label.lower()}-{stamp}","password":"correct-password",
        "nickname":label,"deviceId":device_id})
    assert status==201,(status,value); return value

owner=register("Owner",device); member=register("Member",str(uuid.uuid4())); token=owner["accessToken"]
status,group=call("POST","/api/v1/conversations/group",{
    "title":"Stage 5 Gateway","memberIds":[member["user"]["id"]]},token)
assert status==201,(status,group); conversation=group["id"]

status,ticket=call("POST","/api/v1/ws/tickets",{},token)
assert status==201 and ticket["websocketUrl"].startswith("ws://"),(status,ticket)
denied=WebSocket(ticket["websocketUrl"],"https://attacker.example")
assert denied.status==403,denied.status

ws=WebSocket(ticket["websocketUrl"]); assert ws.status==101,ws.status
ready=ws.recv_json(); assert ready["op"]=="READY" and ready["data"]["deviceId"]==device,ready
reused=WebSocket(ticket["websocketUrl"]); assert reused.status==401,reused.status

ping_id=str(uuid.uuid4()); ws.send_json({"v":1,"op":"PING","requestId":ping_id,"data":{}})
pong=ws.recv_json(); assert pong["op"]=="PONG" and pong["requestId"]==ping_id,pong

client_message=uuid7(); request_id=str(uuid.uuid4())
send={"v":1,"op":"SEND","requestId":request_id,"data":{
    "conversationId":conversation,"clientMsgId":client_message,"type":"TEXT",
    "content":{"text":"sent through Netty"},"attachmentIds":[]}}
ws.send_json(send); ack=ws.recv_json()
assert ack["op"]=="SEND_ACK" and ack["requestId"]==request_id and not ack["data"]["replayed"],ack
send["requestId"]=str(uuid.uuid4()); ws.send_json(send); replay=ws.recv_json()
assert replay["op"]=="SEND_ACK" and replay["data"]["replayed"] and replay["data"]["messageId"]==ack["data"]["messageId"],replay

bad_id=str(uuid.uuid4()); ws.send_json({"v":1,"op":"UNKNOWN","requestId":bad_id,"data":{}})
error=ws.recv_json(); assert error["op"]=="ERROR" and error["data"]["code"]=="UNSUPPORTED_OPERATION",error
status,history=call("GET",f"/api/v1/conversations/{conversation}/messages?afterSeq=0&limit=10",token=token)
assert status==200 and any(item["id"]==ack["data"]["messageId"] for item in history["items"]),(status,history)

status,next_ticket=call("POST","/api/v1/ws/tickets",{},token); assert status==201,(status,next_ticket)
next_ws=WebSocket(next_ticket["websocketUrl"]); assert next_ws.status==101,next_ws.status
next_ready=next_ws.recv_json(); assert next_ready["op"]=="READY",next_ready
reconnect=None
for _ in range(10):
    candidate=ws.recv_json()
    if candidate["op"]=="RECONNECT":
        reconnect=candidate; break
assert reconnect and reconnect["data"]["reason"]=="CONNECTION_REPLACED",reconnect
for _ in range(70):
    next_ws.send_json({"v":1,"op":"PING","requestId":str(uuid.uuid4()),"data":{}})
rate_responses=[next_ws.recv_json() for _ in range(70)]
assert any(value["op"]=="ERROR" and value["data"]["code"]=="RATE_LIMITED" for value in rate_responses),rate_responses
next_ws.send_frame(1,b"x"*66000)
opcode,close_payload=next_ws.recv_frame()
while opcode==1:
    opcode,close_payload=next_ws.recv_frame()
assert opcode==8 and struct.unpack("!H",close_payload[:2])[0]==1009,(opcode,close_payload)
next_ws.close(); ws.close()

print("PASS: one-time WebSocket ticket and strict Origin validation")
print("PASS: READY, PING/PONG and stable protocol error envelope")
print("PASS: WebSocket SEND uses the reliable message transaction and idempotent replay")
print("PASS: a newer connection replaces the same user-device connection")
print("PASS: per-connection rate limit and 64 KiB frame limit")
