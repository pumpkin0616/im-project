#!/usr/bin/env python3
import json, time, urllib.error, urllib.request, uuid

BASE="http://127.0.0.1:18080"
def call(method,path,body=None,token=None):
    headers={"Accept":"application/json"}; data=None
    if body is not None: headers["Content-Type"]="application/json"; data=json.dumps(body).encode()
    if token: headers["Authorization"]="Bearer "+token
    request=urllib.request.Request(BASE+path,data=data,headers=headers,method=method)
    try:
        with urllib.request.urlopen(request,timeout=15) as response:
            raw=response.read(); return response.status,json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw=error.read(); return error.code,json.loads(raw) if raw else None

stamp=str(int(time.time()*1000))
def register(label):
    status,value=call("POST","/api/v1/auth/register",{
        "account":f"stage3-{label}-{stamp}","password":"correct-password",
        "nickname":label,"deviceId":str(uuid.uuid4())})
    assert status==201,(status,value); return value

owner=register("Owner"); alice=register("Alice"); bob=register("Bob"); carol=register("Carol")
to=owner["accessToken"]; ta=alice["accessToken"]; tb=bob["accessToken"]

status,request=call("POST","/api/v1/friend-requests",{"recipientId":alice["user"]["id"],"note":"direct test"},to)
assert status==201,(status,request)
status,_=call("POST",f"/api/v1/friend-requests/{request['id']}/accept",{},ta); assert status==200,status

status,direct=call("POST","/api/v1/conversations/direct",{"peerUserId":alice["user"]["id"]},to)
assert status==201 and direct["type"]=="DIRECT" and direct["memberCount"]==2,(status,direct)
status,reused=call("POST","/api/v1/conversations/direct",{"peerUserId":alice["user"]["id"]},to)
assert status==201 and reused["id"]==direct["id"],(status,reused)

status,group=call("POST","/api/v1/conversations/group",{
    "title":"Stage 3 Group","memberIds":[alice["user"]["id"],bob["user"]["id"]]},to)
assert status==201 and group["myRole"]=="OWNER" and group["memberCount"]==3,(status,group)
gid=group["id"]
status,denied=call("PATCH",f"/api/v1/conversations/{gid}",{"title":"Denied"},ta)
assert status==403,(status,denied)
status,group=call("PUT",f"/api/v1/conversations/{gid}/members/{alice['user']['id']}/role",{"role":"ADMIN"},to)
assert status==200 and any(x["role"]=="ADMIN" for x in group["members"] if x["userId"]==alice["user"]["id"]),(status,group)
status,group=call("PATCH",f"/api/v1/conversations/{gid}",{"title":"Renamed Group"},ta)
assert status==200 and group["title"]=="Renamed Group",(status,group)
status,group=call("POST",f"/api/v1/conversations/{gid}/members",{"userIds":[carol["user"]["id"]]},ta)
assert status==200 and group["memberCount"]==4,(status,group)
status,_=call("DELETE",f"/api/v1/conversations/{gid}/members/{bob['user']['id']}",token=ta)
assert status==204,status
status,_=call("GET",f"/api/v1/conversations/{gid}",token=tb); assert status==403,status
status,page=call("GET","/api/v1/conversations",token=to)
assert status==200 and {direct["id"],gid}.issubset({x["id"] for x in page["items"]}),(status,page)
status,_=call("POST",f"/api/v1/conversations/{gid}/leave",{},ta); assert status==204,status
status,_=call("POST",f"/api/v1/conversations/{gid}/dissolve",{},to); assert status==204,status
status,_=call("GET",f"/api/v1/conversations/{gid}",token=to); assert status in (403,422),status

print("PASS: unique direct conversation creation")
print("PASS: group creation, directory listing and member detail")
print("PASS: owner/admin permissions, add/remove member and role change")
print("PASS: member leave and owner dissolve")
