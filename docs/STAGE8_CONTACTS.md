# 第八阶段：联系人与好友申请

本阶段把 `users.account` 明确为用户可自定义、全局唯一的 **IM ID**。注册和搜索时统一转为小写，因此用户输入不区分大小写。Web 左侧新增“联系人”入口，提供账号搜索、好友申请、好友目录和关系管理。

## 页面能力

- 注册和个人资料区域明确展示 IM ID。
- 左侧“消息 / 联系人”导航；联系人入口显示未处理申请数量。
- 按 IM ID 搜索用户，完全匹配优先，同时支持前缀结果，最多返回 20 人。
- 搜索结果根据关系显示“添加、接受、已申请、发消息、已拉黑或这是你”。
- 展示收到和发出的好友申请，收到的待处理申请可接受或拒绝。
- 好友列表支持按昵称或 IM ID 过滤。
- 点击好友资料后可创建或打开唯一单聊。
- 删除好友会双向解除关系；拉黑会解除关系、取消双方待处理申请，并阻止后续申请。
- 联系人和申请每 30 秒静默刷新，也可手动刷新。

## API

新增用户搜索接口：

```http
GET /api/v1/users/search?q={IM_ID}&limit=20
Authorization: Bearer <accessToken>
```

搜索词长度为 3 到 128 个字符。`relationship` 可能为：`SELF`、`FRIEND`、`INCOMING_PENDING`、`OUTGOING_PENDING`、`BLOCKED`、`NONE`。收到待处理申请时会返回 `pendingRequestId`，前端可直接接受。

好友申请响应新增 `requesterAccount` 和 `recipientAccount`，便于联系人页面展示双方 IM ID。其余接口继续使用：

- `POST /api/v1/friend-requests`
- `GET /api/v1/friend-requests?direction=incoming|outgoing`
- `POST /api/v1/friend-requests/{id}/accept`
- `POST /api/v1/friend-requests/{id}/reject`
- `DELETE /api/v1/friend-requests/{id}`
- `GET /api/v1/friends`
- `DELETE /api/v1/friends/{userId}`
- `PUT /api/v1/blocks/{userId}`
- `GET /api/v1/blocks`
- `DELETE /api/v1/blocks/{userId}`
- `POST /api/v1/conversations/direct`

## 本地验证

```bash
mvn test
cd web && npm run build && cd ..
docker compose --profile app --profile web up -d --build app web
python3 scripts/stage8-contacts-smoke-test.py
```

脚本通过 Web 的 Nginx 代理注册两个临时用户，并验证搜索、申请状态、待处理列表、接受、好友列表、唯一单聊、双向删除和拉黑限制。
