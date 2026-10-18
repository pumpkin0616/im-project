# 第十二阶段：高可用、压测与灰度上线

## 1. 交付目标

- 应用和 Netty 网关至少 3 副本，跨主机、跨可用区调度。
- 1000 个 WebSocket 同时在线，单实例退出时客户端可重连到其他实例。
- 常态 3,000 条/秒、峰值 9,000 条/秒固定到达率验收。
- 24 小时长稳、热点会话、重连风暴和 1,620 万条 RocketMQ 积压恢复。
- MySQL、Redis、RocketMQ和应用实例故障演练。
- 可观测的 Outbox 延迟、网关拥塞、接口错误率和延迟 SLO。
- 先 Canary，再按 50、200、1000 人逐级放量，可随时回滚。

## 2. 高可用拓扑

生产清单位于 `deploy/k8s/base`：

- `im-app` 默认 3 副本，HPA 范围 3～12。
- RollingUpdate 使用 `maxUnavailable=0`，PDB 保证至少 2 个副本。
- Pod 同时承载 Spring HTTP 和 Netty WebSocket，跨区及跨节点分散。
- HTTP 与 WebSocket 分别由 Service/Ingress 转发。
- Pod IP 写入 Redis 在线路由，RocketMQ 消费者可把消息 Dispatch 到持有连接的具体 Pod。
- 收到终止信号后先摘除 readiness，预留 10 秒负载均衡传播时间，再由 Netty 向客户端发送 1012 关闭码。
- 附件和 Parquet 目录使用 RWX 共享卷。生产也可以把该目录替换成受控的共享对象存储挂载。
- 自动归档和生命周期调度只在单副本 `im-lifecycle-worker` 中启用，避免应用副本重复执行批次；分区过程自身仍使用数据库锁。

MySQL 使用托管高可用实例并开启同步 binlog，Redis 使用带自动故障转移的主从/集群，RocketMQ 至少使用多 Master 及同步副本。数据库、Redis、RocketMQ 不部署在本清单中，连接地址由 `im-config` 指向托管服务。

应用目前以一个物理消息分片 `shard-0` 为部署单元。新增物理分片时，每个分片建立独立应用池和数据库实例，并由入口路由到对应分片池；在入口具备跨分片请求路由前，不得把不同 `IM_LOCAL_SHARD_ID` 的 Pod 混入同一个 Service。

## 3. 部署

先创建生产 Secret，示例文件不能直接用于生产：

```bash
kubectl apply -f deploy/k8s/base/namespace.yaml
cp deploy/k8s/base/secret.example.yaml /tmp/im-secret.yaml
# 替换 /tmp/im-secret.yaml 中所有 replace-me，再执行：
kubectl apply -f /tmp/im-secret.yaml
```

修改域名、托管依赖地址、RWX StorageClass 和不可变镜像摘要，然后检查并部署：

```bash
kubectl kustomize deploy/k8s/base >/tmp/im-rendered.yaml
kubectl apply --server-side --dry-run=server -f /tmp/im-rendered.yaml
kubectl apply -k deploy/k8s/base
kubectl -n im rollout status deployment/im-app --timeout=10m
```

本地双副本验证：

```bash
mvn package
docker compose -f compose.yaml -f compose.ha.yaml --profile ha up -d --build
curl http://127.0.0.1:28080/actuator/health/readiness
```

## 4. 监控与告警

新增指标：

| 指标 | 含义 |
|---|---|
| `im_outbox_backlog` | 待发布及租约中的事件数 |
| `im_outbox_oldest_unpublished_seconds` | 最老未发布事件年龄 |
| `im_outbox_manual` | 需要人工处理的事件数 |
| `im_outbox_inflight` | 当前异步发布中的事件数 |
| `im_outbox_metrics_refresh_failures_total` | Outbox 指标刷新失败数 |

`ops/prometheus/im-slo-alerts.yml` 覆盖 API 5xx、发送 p99、实例掉线、Outbox 积压、实时投递失败、网关繁忙和连接池等待。Prometheus 还应采集 MySQL 主从延迟、Redis 故障转移、RocketMQ commitlog/消费积压及节点资源。

## 5. 压测矩阵

| 场景 | 负载 | 验收标准 |
|---|---:|---|
| 脚本冒烟 | 50 条/秒，30 秒 | 无错误、无丢弃迭代 |
| 常态写入 | 3,000 条/秒，10 分钟 | 错误率 <0.1%，p95 <200ms，p99 <500ms |
| 峰值写入 | 9,000 条/秒，15 分钟 | 错误率 <0.1%，p99 <1s，无丢弃迭代 |
| 长稳 | 3,000 条/秒，24 小时 | 无数据丢失，错误率 <0.1%，内存和延迟无持续增长 |
| WebSocket | 1000 连接，10 分钟 | READY 100%，连接 p95 <2s |
| 重连风暴 | 200 VU 连续重连 5 分钟 | 成功率 >99.5%，连接 p99 <3s |
| 热点会话 | 单会话 500 条/秒，5 分钟 | seq 连续唯一，p99 <1s |
| MQ 积压恢复 | 1,620 万 Outbox 事件 | 恢复后 30 分钟内清空，无 manual、无消息缺失 |

固定 RPS 使用 k6 开放模型。Postman 集合只用于基于 VU 的快速门禁，结果不能替代固定到达率验收。命令和数据准备见 `load/README.md`。

压测后执行数据正确性检查：

```bash
mysql -uroot -p < sql/900_verify_message_correctness.sql
scripts/stage12-readiness-check.sh
```

## 6. 1,620 万积压恢复

在隔离环境停止 RocketMQ 发布入口，保持数据库可写，以 9,000 条/秒运行 30 分钟形成约 1,620 万条 Outbox。确认 API 延迟和 MySQL 磁盘仍在阈值内后恢复 RocketMQ：

1. `im_outbox_backlog` 必须开始单调下降。
2. `im_outbox_oldest_unpublished_seconds` 最终回到 60 秒以内。
3. `im_outbox_manual` 必须保持为 0。
4. 所有 Outbox 最终为 published，按 `event_id` 检查没有丢失。
5. 抽样会话的消息 `seq`、历史查询和在线/离线补偿一致。

本地故障脚本只生成小规模积压，用于验证恢复机制，不能证明 1,620 万容量。

## 7. 故障演练

本地 Compose 环境：

```bash
ALLOW_LOCAL_CHAOS=1 scripts/stage12-chaos-test.sh
```

脚本依次验证应用重启、Redis 中断、RocketMQ 中断和 MySQL 中断。MySQL 不可用时 readiness 必须下降；Redis/RocketMQ 不可用时可靠写入仍可提交，分别依靠历史补偿和 Outbox 恢复。

Kubernetes 测试环境删除一个稳定 Pod：

```bash
KUBE_CONTEXT=staging scripts/stage12-k8s-failure-drill.sh
```

生产故障演练一次只注入一个故障，并在 SLO、数据库复制、Outbox、消费者积压及客户端重连恢复后再执行下一项。

## 8. Canary 与灰度

`deploy/k8s/canary` 增加一个 Canary Pod。带请求头 `X-IM-Canary: always` 的测试请求进入 Canary，其他流量仍进入稳定版本。

```bash
kubectl apply -k deploy/k8s/canary
KUBE_CONTEXT=production IMAGE='registry.example/im-app@sha256:...' \
BASE_URL='https://im.example.com' ACCESS_TOKEN='...' CONVERSATION_ID='...' \
scripts/stage12-canary-rollout.sh
```

发布步骤：

1. Canary 测试账号验证注册、登录、发送、历史、WebSocket 和撤回。
2. 观察 15 分钟，要求 5xx、p99、Outbox 和 JVM 指标无回归。
3. 稳定 Deployment 滚动更新，先开放 50 名内部用户。
4. 观察 30 分钟后扩大到 200 人，再观察 1 小时。
5. 扩大到 1000 人；至少观察一个业务高峰。
6. 任一关键 SLO 超阈值时停止放量并执行 `kubectl rollout undo deployment/im-app`。

数据库迁移必须先向后兼容，Canary 和旧版本能同时运行。删除字段、收紧约束和清理旧数据放在全量稳定后的后续发布中。

## 9. 上线验收清单

- [ ] 三副本跨主机/跨区，PDB 和 HPA 生效。
- [ ] 托管 MySQL、Redis、RocketMQ 完成主从或节点切换演练。
- [ ] 1000 连接、3000/9000 条每秒和热点会话达到阈值。
- [ ] 24 小时长稳完成，压测机没有成为瓶颈。
- [ ] 1,620 万积压在 30 分钟内恢复。
- [ ] `sql/900_verify_message_correctness.sql` 无异常。
- [ ] Prometheus 告警已加载并进行触发测试。
- [ ] Canary、50/200/1000 人灰度及回滚演练完成。
- [ ] 压测账号、临时长 TTL 和测试数据已撤销或清理。
