# 第五阶段 Netty WebSocket 网关

第五阶段提供浏览器实时上行链路。HTTP API 继续监听 `18080`，Netty WebSocket 网关监听 `18081`。

## 建立连接

浏览器不能把长期 JWT 放入 WebSocket URL。客户端先携带访问令牌申请一个 60 秒、只能使用一次的连接票据：

```http
POST /api/v1/ws/tickets
Authorization: Bearer <accessToken>
```

响应：

```json
{
  "ticketId": "UUID",
  "ticket": "one-time-secret",
  "websocketUrl": "ws://127.0.0.1:18081/ws?ticket=one-time-secret",
  "expiresAt": "2026-10-17T15:00:00Z"
}
```

浏览器随后连接 `websocketUrl`。网关严格校验 `Origin`，并在有界业务线程池中原子消费票据；数据库访问不会进入 Netty EventLoop。同一用户和设备建立新连接时，旧连接先收到 `RECONNECT`，再以关闭码 `4001` 断开。

本地示例：

```javascript
const ticket = await fetch("http://127.0.0.1:18080/api/v1/ws/tickets", {
  method: "POST",
  headers: {Authorization: `Bearer ${accessToken}`}
}).then(response => response.json());

const socket = new WebSocket(ticket.websocketUrl);
socket.onmessage = event => console.log(JSON.parse(event.data));
```

## 协议

客户端文本帧统一使用：

```json
{
  "v": 1,
  "op": "SEND",
  "requestId": "UUID",
  "data": {}
}
```

| 操作 | 方向 | 说明 |
|---|---|---|
| `READY` | 服务端→客户端 | 握手完成，返回连接、网关启动和心跳信息 |
| `PING` / `PONG` | 双向 | 应用层心跳；也支持 WebSocket Ping/Pong 控制帧 |
| `SEND` / `SEND_ACK` | 双向 | 通过既有消息事务发送并确认消息 |
| `RECEIVED_ACK` | 双向 | 接收确认的协议占位，第六阶段接入投递状态 |
| `ERROR` | 服务端→客户端 | 稳定错误码、是否可重试及可选等待时间 |
| `RECONNECT` | 服务端→客户端 | 连接被替换或需要迁移 |

`SEND.data` 与 HTTP 消息发送参数一致：

```json
{
  "conversationId": "UUID",
  "clientMsgId": "UUIDv7",
  "type": "TEXT",
  "content": {"text": "你好"},
  "attachmentIds": []
}
```

`SEND` 直接复用现有 `SendMessageHandler`，因此 HTTP 与 WebSocket 共享 UUIDv7 幂等、严格会话序号、消息/附件/Outbox 同事务和 RocketMQ 发布语义。

## 并发和保护参数

| 参数 | 默认值 |
|---|---:|
| Boss / EventLoop | 1 / 8 |
| 心跳建议间隔 | 25 秒 |
| 无读取活动断开 | 75 秒 |
| 文本帧上限 | 64 KiB |
| 写缓冲低/高水位 | 32 / 128 KiB |
| 单连接待发硬上限 | 256 KiB |
| 慢连接关闭时间 | 10 秒 |
| 单连接业务请求在途 | 16 |
| 网关业务请求在途 | 1024 |
| 有界业务队列 | 1024 |
| 单连接限流 | 30 次/秒，突发 60 次 |

业务池满或达到在途上限时返回 `GATEWAY_BUSY`。超过连接速率时返回 `RATE_LIMITED`。持续不可写的连接关闭并由历史接口补拉恢复。

Prometheus 指标包括：

- `im_gateway_connections_active`
- `im_gateway_handshakes_rejected_total`
- `im_gateway_operations_received_total`
- `im_gateway_operations_rate_limited_total`
- `im_gateway_operations_busy_total`

## 当前边界

当前连接注册表位于单个网关进程内，已经能保证本实例内同设备连接替换。第六阶段将加入 Redis 租约路由、RocketMQ 投递消费者和跨网关 Dispatch；Redis 故障不会影响消息写入及历史恢复。

## 验收

```bash
docker compose exec -T mysql mysql -uroot -plocal-root < sql/013_stage5_gateway_schema.sql
mvn clean test package
docker compose --profile app up -d --build app
python3 scripts/stage5-smoke-test.py
```
