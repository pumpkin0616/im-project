#!/usr/bin/env python3
import argparse
import concurrent.futures
import json
import pathlib
import time
import urllib.error
import urllib.request
import uuid


def call(base, method, path, body=None, token=None):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, json.loads(raw) if raw else None


def require(result, expected):
    status, value = result
    if status != expected:
        raise RuntimeError(f"expected HTTP {expected}, got {status}: {value}")
    return value


def main():
    parser = argparse.ArgumentParser(description="Create isolated Stage 12 load-test identities")
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--send-conversations", type=int, default=128)
    parser.add_argument("--websocket-users", type=int, default=1000)
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--output-dir", default="load/generated")
    args = parser.parse_args()
    if args.send_conversations < 1 or args.websocket_users < 1:
        parser.error("fixture counts must be positive")

    stamp = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"

    def register(index):
        value = require(call(args.base_url, "POST", "/api/v1/auth/register", {
            "account": f"load-{stamp}-{index}", "password": "load-test-password",
            "nickname": f"Load {index}", "deviceId": str(uuid.uuid4()),
        }), 201)
        return value

    total = max(args.websocket_users, args.send_conversations * 2)
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        users = list(pool.map(register, range(total)))

    def create_conversation(index):
        owner, peer = users[index * 2], users[index * 2 + 1]
        request = require(call(args.base_url, "POST", "/api/v1/friend-requests",
                               {"recipientId": peer["user"]["id"], "note": "load test"},
                               owner["accessToken"]), 201)
        require(call(args.base_url, "POST", f"/api/v1/friend-requests/{request['id']}/accept", {},
                     peer["accessToken"]), 200)
        conversation = require(call(args.base_url, "POST", "/api/v1/conversations/direct",
                                    {"peerUserId": peer["user"]["id"]}, owner["accessToken"]), 201)
        return [
            {"conversationId": conversation["id"], "accessToken": owner["accessToken"]},
            {"conversationId": conversation["id"], "accessToken": peer["accessToken"]},
        ]

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        pairs = list(pool.map(create_conversation, range(args.send_conversations)))

    send_identities = [identity for pair in pairs for identity in pair]
    ws_identities = [{"accessToken": user["accessToken"]} for user in users[:args.websocket_users]]
    output = pathlib.Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    (output / "send-identities.json").write_text(json.dumps(send_identities), encoding="utf-8")
    (output / "ws-identities.json").write_text(json.dumps(ws_identities), encoding="utf-8")
    print(f"Created {len(send_identities)} send identities across {args.send_conversations} conversations")
    print(f"Created {len(ws_identities)} distinct WebSocket device sessions")
    print(f"Secrets written under {output}; this directory is ignored by Git")


if __name__ == "__main__":
    main()
