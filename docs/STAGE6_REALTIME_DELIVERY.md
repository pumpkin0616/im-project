# 第六阶段：实时投递、离线同步与已读回执

本阶段把“可靠写入”接到“在线可见”：Outbox 发布的 `MESSAGE_CREATED` 由 RocketMQ 消费者读取，消费者从 MySQL 装载消息及当前可见成员，批量查询 Redis 在线路由，再向对应网关投递 `MESSAGE`。MQ、Redis 或在线推送短暂异常不会破坏消息主记录，客户端重连后通过同步接口按 `seq` 补拉。

## 运行链路

1. 客户端通过 HTTP 或 WebSocket `SEND` 写入消息。
2. 消息、幂等记录和 Outbox 在同一个 MySQL 事务提交。
3. Outbox Worker 以 `conversationId` 为 message group 发布 FIFO `MESSAGE_CREATED`。
4. `im-realtime-delivery-v1` 消费组装载消息和附件，并筛选 `joined_seq < message.seq` 的活跃成员。
5. Redis 以用户为批次查询有效设备租约，并按 `gatewayId + dispatchUrl` 分组。
6. 本机连接直接进入 Netty EventLoop；其他网关使用内部批量 Dispatch 接口。
7. 消费者在网关接受投递后 ACK RocketMQ。重复投递允许发生，Web 客户端必须按 `messageId` 去重。

Redis 只保存在线加速数据。路由查询失败时消费者完成本次在线投递，消息仍可由历史接口补拉，不影响发送事务。每个连接租约默认 90 秒，30 秒续期；注销通过“路由值仍等于当前 connectionId”条件删除，旧连接无法误删替代它的新连接。

## Redis 键

| 键 | 类型 | 内容 | TTL |
|---|---|---|---:|
| `im:online:route:{userId}:{deviceId}` | String(JSON) | `connectionId`、`gatewayId`、`gatewayBootId`、`dispatchUrl` | 90 秒 |
| `im:online:user:{userId}` | Sorted Set | member 为 `deviceId`，score 为租约过期毫秒 | 180 秒 |

群消息查询在线路由时，对所有用户的 Sorted Set 使用一次 Redis pipeline，再用一次 MGET 读取路由，避免按成员串行往返。

## WebSocket 服务端事件

### `MESSAGE`

```json
{
  "v": 1,
  "op": "MESSAGE",
  "data": {
    "conversationId": "...",
    "messageId": "...",
    "seq": "42",
    "senderId": "...",
    "clientMsgId": "...",
    "type": "TEXT",
    "content": {"text": "hello"},
    "createdAt": "2026-10-17T15:00:00Z",
    "attachments": []
  }
}
```

### `READ`

客户端提交已读高水位：

```json
{
  "v": 1,
  "op": "READ",
  "requestId": "UUID",
  "data": {"conversationId": "...", "seq": "42"}
}
```

服务端返回或推送 `READ_UPDATE`。`last_read_seq` 使用 `GREATEST` 单调更新，并将超出会话高水位的值收敛到 `last_seq`。单聊把更新推给双方在线设备；群聊第一版只更新和推送读取者自己的设备。

## HTTP 接口

### 批量离线同步

`POST /api/v1/sync`

```bash
curl -sS -X POST 'http://127.0.0.1:18080/api/v1/sync' \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  --data "{
    \"conversations\": [
      {\"conversationId\": \"$CONVERSATION_ID\", \"afterSeq\": 0}
    ],
    \"limitPerConversation\": 50
  }"
```

一次最多同步 20 个会话，每个会话最多返回 100 条。响应复用历史消息页，包含 `hasMore`、`nextAfterSeq`、`minAvailableSeq`、`visibleFromSeq` 和 `highWatermark`；若 `hasMore=true`，客户端继续用新的 `afterSeq` 拉取。

### HTTP 已读更新

`PUT /api/v1/conversations/{conversationId}/read`

```bash
curl -sS -X PUT "http://127.0.0.1:18080/api/v1/conversations/$CONVERSATION_ID/read" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"seq":42}'
```

### 内部 Dispatch

`POST /internal/v1/gateway/dispatch` 仅供网关间调用，要求 `X-IM-Dispatch-Secret`。入口网关或 Service Mesh 应限制该路径只能从服务网络访问。单次最多 1,000 个连接 ID。

```bash
curl -sS -X POST 'http://app:8080/internal/v1/gateway/dispatch' \
  -H "X-IM-Dispatch-Secret: $IM_GATEWAY_DISPATCH_SECRET" \
  -H 'Content-Type: application/json' \
  --data '{"connectionIds":["connection-id"],"operation":"MESSAGE","data":{}}'
```

当前实现把跨网关传输封装在 `RealtimeDeliveryRouter` 中，使用持久 HTTP 连接和批量连接 ID。部署规模扩大后可在不改变 Redis 路由、MQ 消费和 WebSocket 协议的情况下替换为 gRPC 长连接适配器。

## 配置与部署

| 环境变量 | 本地值 | 说明 |
|---|---|---|
| `IM_REDIS_HOST` / `IM_REDIS_PORT` | `redis:6379` | Redis 地址 |
| `IM_GATEWAY_ID` | `gateway-local-1` | 每个网关实例唯一标识 |
| `IM_GATEWAY_DISPATCH_URL` | `http://app:8080/internal/v1/gateway/dispatch` | 其他网关可访问的实例地址 |
| `IM_GATEWAY_DISPATCH_SECRET` | 本地开发密钥 | 生产环境从 Secret 注入并轮换 |
| `IM_ONLINE_LEASE_TTL` | `90s` | 在线租约 TTL |
| `IM_ONLINE_RENEW_INTERVAL` | `30s` | 续租间隔 |
| `IM_ROCKETMQ_CONSUMER_GROUP` | `im-realtime-delivery-v1` | 实时投递消费组 |
| `IM_ROCKETMQ_CONSUMER_THREADS` | `32` | 单实例消费线程数 |

生产环境每个网关实例必须使用不同的 `IM_GATEWAY_ID` 和可直达本实例的 `IM_GATEWAY_DISPATCH_URL`。建议至少 3 个网关副本，跨可用区分散，滚动发布时先摘除新连接、等待现有连接迁移，再停止进程。Redis 使用托管主从或 Cluster；RocketMQ 使用多副本 Broker，并让 Topic 队列数覆盖投递消费者总并发。

## 本地验证

```bash
mvn test
mvn package
docker compose --profile app up -d --build
python3 scripts/stage6-smoke-test.py
```

脚本验证 Redis 在线租约、RocketMQ 双端实时投递、单聊已读推送、断线补拉、已读游标单调性和内部接口鉴权。可额外检查：

```bash
docker compose exec redis redis-cli --scan --pattern 'im:online:*'
curl -sS http://127.0.0.1:18080/actuator/prometheus | grep -E 'im_online|im_delivery'
```

主要指标为 `im_online_redis_failures_total`、`im_delivery_events_success_total`、`im_delivery_events_failure_total` 和 `im_delivery_connections_dispatched_total`。验收时应同时观察 RocketMQ 消费堆积、Redis P99、Dispatch 错误率、WebSocket 出站积压以及离线补拉差值。
