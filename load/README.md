# 第十二阶段压测工具

`k6-send.js` 使用开放模型固定到达率，正式写入验收包含：

- `normal`：3,000 条/秒，默认 10 分钟。
- `peak`：9,000 条/秒，默认 15 分钟。
- `acceptance`：先常态再峰值。
- `soak`：3,000 条/秒，默认 24 小时。
- `smoke`：50 条/秒、30 秒，仅检查脚本和环境。

准备隔离数据：

```bash
python3 scripts/stage12-load-fixtures.py --send-conversations 128 --websocket-users 1000
export IM_IDENTITIES="$(cat load/generated/send-identities.json)"
load/run-stage12.sh smoke
load/run-stage12.sh normal
load/run-stage12.sh peak
```

`load/generated` 含访问令牌并已加入 `.gitignore`。正式 25 分钟验收环境把访问令牌 TTL 设为至少 30 分钟；24 小时长稳环境使用专用测试账号并将 TTL 设为至少 26 小时，结束后立即撤销会话。不要改生产用户的令牌策略。

1000 个 WebSocket 连接：

```bash
export WS_IDENTITIES="$(cat load/generated/ws-identities.json)"
k6 run -e WS_IDENTITIES="$WS_IDENTITIES" -e CONNECTIONS=1000 load/k6-websocket.js
```

从 Docker 内运行 k6 时增加 `-e WS_BASE_URL=ws://host.docker.internal:18081/ws`，使票据中的本机地址改写到宿主机。

重连风暴与热点会话：

```bash
k6 run -e WS_IDENTITIES="$WS_IDENTITIES" -e RECONNECT_VUS=200 load/k6-reconnect.js
k6 run -e ACCESS_TOKEN="$ACCESS_TOKEN" -e CONVERSATION_ID="$CONVERSATION_ID" \
  -e HOT_RATE=500 load/k6-hot-conversation.js
```

Postman 性能集合用于 VU 延迟门禁和快速回归；它不能证明固定 3,000/9,000 条每秒容量：

```bash
load/run-postman-performance.sh smoke
```

正式验收从独立压测机执行，入口指向负载均衡器，并同时采集 Prometheus、MySQL、Redis 和 RocketMQ 指标。生成端 CPU、网络饱和或 `dropped_iterations` 非零时，本次结果不能归因于服务端容量。
