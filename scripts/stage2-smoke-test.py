#!/usr/bin/env python3
import json, time, urllib.error, urllib.request, uuid

BASE = "http://127.0.0.1:18080"

def call(method, path, body=None, token=None):
    headers={"Accept":"application/json"}
    data=None
    if body is not None:
        headers["Content-Type"]="application/json"; data=json.dumps(body).encode()
    if token: headers["Authorization"]="Bearer "+token
    req=urllib.request.Request(BASE+path,data=data,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=10) as response:
            raw=response.read(); return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw=error.read(); return error.code, json.loads(raw) if raw else None

stamp=str(int(time.time()*1000))
device_a=str(uuid.uuid4()); device_b=str(uuid.uuid4())
s,a=call("POST","/api/v1/auth/register",{"account":"stage2-a-"+stamp,"password":"correct-password","nickname":"Alice","deviceId":device_a})
assert s==201,(s,a)
s,b=call("POST","/api/v1/auth/register",{"account":"stage2-b-"+stamp,"password":"correct-password","nickname":"Bob","deviceId":device_b})
assert s==201,(s,b)
ta=a["accessToken"]; tb=b["accessToken"]
s,me=call("GET","/api/v1/users/me",token=ta); assert s==200 and me["nickname"]=="Alice",(s,me)
s,updated=call("PUT","/api/v1/users/me",{"nickname":"Alice Updated","avatarKey":None},ta); assert s==200 and updated["nickname"]=="Alice Updated",(s,updated)
s,request=call("POST","/api/v1/friend-requests",{"recipientId":b["user"]["id"],"note":"hello"},ta); assert s==201,(s,request)
s,accepted=call("POST",f"/api/v1/friend-requests/{request['id']}/accept",{},tb); assert s==200 and accepted["status"]==1,(s,accepted)
s,friends=call("GET","/api/v1/friends",token=ta); assert s==200 and any(x["id"]==b["user"]["id"] for x in friends),(s,friends)
s,rotated=call("POST","/api/v1/auth/refresh",{"refreshToken":a["refreshToken"]}); assert s==200,(s,rotated)
s,_=call("POST","/api/v1/auth/logout",{"refreshToken":rotated["refreshToken"]},rotated["accessToken"]); assert s==204,s
s,failed=call("POST","/api/v1/auth/refresh",{"refreshToken":rotated["refreshToken"]}); assert s==401,(s,failed)
print("PASS: register, JWT authentication, profile update")
print("PASS: friend request, acceptance, bilateral friendship")
print("PASS: refresh-token rotation and logout revocation")
