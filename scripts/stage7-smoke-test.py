#!/usr/bin/env python3
import json, re, time, urllib.error, urllib.request, uuid

BASE = "http://127.0.0.1:3000"

def request(method, path, body=None, token=None):
    headers = {"Accept": "application/json"}; data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if token: headers["Authorization"] = "Bearer " + token
    value = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(value, timeout=15) as response:
        raw = response.read()
        return response.status, response.headers.get("Content-Type", ""), raw

status, content_type, index = request("GET", "/")
assert status == 200 and "text/html" in content_type and b'<div id="root"></div>' in index
assets = re.findall(rb'(?:src|href)="(/assets/[^"]+)"', index)
assert len(assets) >= 2, assets
for asset in assets:
    status, _, body = request("GET", asset.decode())
    assert status == 200 and len(body) > 1000, (asset, status, len(body))

status, content_type, fallback = request("GET", "/conversations/example")
assert status == 200 and "text/html" in content_type and b'<div id="root"></div>' in fallback

stamp = str(int(time.time() * 1000))
status, _, raw = request("POST", "/api/v1/auth/register", {
    "account": "stage7-web-" + stamp,
    "password": "correct-password",
    "nickname": "Web Client",
    "deviceId": str(uuid.uuid4())
})
assert status == 201, (status, raw)
tokens = json.loads(raw)
status, _, raw = request("GET", "/api/v1/conversations?limit=20", token=tokens["accessToken"])
assert status == 200 and isinstance(json.loads(raw)["items"], list)
status, _, raw = request("POST", "/api/v1/ws/tickets", {}, tokens["accessToken"])
ticket = json.loads(raw)
assert status == 201 and ticket["websocketUrl"].startswith("ws://127.0.0.1:18081/ws?ticket=")

print("PASS: production Web assets and SPA fallback are served by Nginx")
print("PASS: /api proxy supports registration and authenticated conversation loading")
print("PASS: Web client can obtain a one-time Netty WebSocket ticket")
