#!/usr/bin/env python3
import hashlib, json, time, urllib.error, urllib.parse, urllib.request, uuid

BASE="http://127.0.0.1:18080"
def call(method,path,body=None,token=None,raw=None):
    headers={"Accept":"application/json"}; data=None
    if raw is not None:
        data=raw; headers["Content-Type"]="application/octet-stream"
    elif body is not None:
        headers["Content-Type"]="application/json"; data=json.dumps(body).encode()
    if token: headers["Authorization"]="Bearer "+token
    request=urllib.request.Request(BASE+path,data=data,headers=headers,method=method)
    try:
        with urllib.request.urlopen(request,timeout=20) as response:
            content=response.read()
            if response.headers.get_content_type()=="application/json":
                return response.status,json.loads(content) if content else None
            return response.status,content
    except urllib.error.HTTPError as error:
        content=error.read()
        try: value=json.loads(content) if content else None
        except json.JSONDecodeError: value=content
        return error.code,value

def uuid7():
    value=(int(time.time()*1000)<<80)|(7<<76)|(uuid.uuid4().int&((1<<76)-1))
    value=(value&~(3<<62))|(2<<62)
    return str(uuid.UUID(int=value))

stamp=str(int(time.time()*1000))
def register(label):
    status,value=call("POST","/api/v1/auth/register",{
        "account":f"stage4-{label.lower()}-{stamp}","password":"correct-password",
        "nickname":label,"deviceId":str(uuid.uuid4())})
    assert status==201,(status,value); return value

owner=register("Owner"); member=register("Member"); late=register("Late"); outsider=register("Outsider")
token=owner["accessToken"]
status,group=call("POST","/api/v1/conversations/group",{
    "title":"Stage 4 Group","memberIds":[member["user"]["id"]]},token)
assert status==201,(status,group)
gid=group["id"]

payload=b"stage-4 attachment\n"
digest=hashlib.sha256(payload).hexdigest()
status,ticket=call("POST",f"/api/v1/conversations/{gid}/attachments",{
    "fileName":"stage4.txt","mimeType":"text/plain","sizeBytes":len(payload),"sha256":digest},token)
assert status==201 and ticket["status"]=="UPLOADING",(status,ticket)
aid=ticket["id"]
status,_=call("PUT",ticket["uploadUrl"],token=token,raw=payload); assert status==204,status
status,attachment=call("POST",ticket["completeUrl"],{},token); assert status==200 and attachment["status"]=="READY",(status,attachment)

status,bad_ticket=call("POST",f"/api/v1/conversations/{gid}/attachments",{
    "fileName":"bad.txt","mimeType":"text/plain","sizeBytes":len(payload),"sha256":"0"*64},token)
assert status==201,(status,bad_ticket)
status,error=call("PUT",bad_ticket["uploadUrl"],token=token,raw=payload)
assert status==422 and error["code"]=="HASH_MISMATCH",(status,error)
status,bad_attachment=call("GET",f"/api/v1/conversations/{gid}/attachments/{bad_ticket['id']}",token=token)
assert status==200 and bad_attachment["status"]=="REJECTED",(status,bad_attachment)

def send(text,attachments=None,message_type="TEXT"):
    status,value=call("POST","/api/v1/messages",{
        "conversationId":gid,"clientMsgId":uuid7(),"type":message_type,
        "content":{"text":text},"attachmentIds":attachments or []},token)
    assert status==201,(status,value); return value

first=send("before late member")
status,_=call("POST",f"/api/v1/conversations/{gid}/members",{"userIds":[late["user"]["id"]]},token)
assert status==200,status
second=send("after late member")
third=send("file message",[aid],"FILE")

status,page=call("GET",f"/api/v1/conversations/{gid}/messages?limit=2",token=token)
assert status==200 and page["hasMore"] and [x["seq"] for x in page["items"]]==[int(second["seq"]),int(third["seq"])],(status,page)
file_message=page["items"][-1]
assert file_message["attachments"][0]["id"]==aid,file_message
status,older=call("GET",f"/api/v1/conversations/{gid}/messages?limit=2&beforeSeq={page['nextBeforeSeq']}",token=token)
assert status==200 and [x["seq"] for x in older["items"]]==[int(first["seq"])],(status,older)
status,newer=call("GET",f"/api/v1/conversations/{gid}/messages?afterSeq={first['seq']}&limit=10",token=token)
assert status==200 and [x["seq"] for x in newer["items"]]==[int(second["seq"]),int(third["seq"])],(status,newer)
status,one=call("GET",f"/api/v1/conversations/{gid}/messages/{third['seq']}",token=token)
assert status==200 and one["attachments"][0]["sha256"]==digest,(status,one)

status,late_page=call("GET",f"/api/v1/conversations/{gid}/messages?limit=20",token=late["accessToken"])
assert status==200 and int(first["seq"]) not in [x["seq"] for x in late_page["items"]],(status,late_page)
status,downloaded=call("GET",attachment["downloadUrl"],token=member["accessToken"])
assert status==200 and downloaded==payload,(status,downloaded)
status,_=call("GET",attachment["downloadUrl"],token=outsider["accessToken"])
assert status==403,status

status,second_group=call("POST","/api/v1/conversations/group",{
    "title":"Stage 4 Cursor Group","memberIds":[member["user"]["id"]]},token)
assert status==201,(status,second_group)
status,directory=call("GET","/api/v1/conversations?limit=1",token=token)
assert status==200 and len(directory["items"])==1 and directory["hasMore"] and directory["nextCursor"],(status,directory)
cursor=urllib.parse.quote(directory["nextCursor"],safe="")
status,next_directory=call("GET",f"/api/v1/conversations?limit=1&cursor={cursor}",token=token)
assert status==200 and len(next_directory["items"])==1 and next_directory["items"][0]["id"]!=directory["items"][0]["id"],(status,next_directory)

print("PASS: attachment init, upload, SHA-256 verification and transactional message binding")
print("PASS: invalid attachment digest is rejected and persisted")
print("PASS: backward and forward sequence cursor history with high watermark")
print("PASS: joined-sequence history boundary and attachment download authorization")
print("PASS: opaque conversation directory page contract")
