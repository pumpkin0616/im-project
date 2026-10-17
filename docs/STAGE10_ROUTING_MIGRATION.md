# 第十阶段：动态路由、在线迁移与生产化

## 1. 本阶段交付

本阶段把原先由代码计算的固定路由升级为控制面路由。业务仍使用 1,024 个稳定逻辑槽，但槽位到物理分片、表组的映射保存在 `im_control.slot_routes` 中；热点会话可以通过 `im_control.conversation_routes` 独立覆盖槽位路由。

已实现：

- 1,024 槽动态路由，以及会话级覆盖路由。
- 本地有界 TTL 缓存；实例每秒轮询全局 `route_version`，变化后清空缓存。
- 槽位和单会话两种在线迁移。
- 初始复制、短暂冻结、增量追平、路由 CAS 切换、结果校验和源数据封存。
- `route_epoch` 写入栅栏，拦截旧实例或旧缓存对源表的脏写。
- 迁移期间暂停相关 Outbox 领取，切换后只从目标表继续投递。
- Flyway V10 迁移文件、可重复执行的 DBA SQL 和全部表组的幂等表预建。
- 路由管理密钥、健康检查、Prometheus 指标和可审计的迁移任务记录。

## 2. 数据模型

### 2.1 控制面

| 表 | 作用 |
|---|---|
| `slot_routes` | 1,024 个槽的当前物理分片、表组、epoch 和状态 |
| `conversation_routes` | 热点会话的独立路由；存在时优先于槽位路由 |
| `routing_metadata` | 单行全局路由版本；路由变化时递增 |
| `migration_jobs` | 迁移范围、源目标、epoch、阶段、校验点和错误记录 |

路由状态为 `ACTIVE(1)`、`MIGRATING(2)`、`DISABLED(3)`。解析顺序是会话覆盖路由、槽位路由。`MIGRATING` 期间读请求仍可命中源路由，写请求是否允许由消息库栅栏决定。

### 2.2 消息分片

`slot_fences` 保护整个槽，`conversation_fences` 保护被独立迁移的会话。每次业务写入都在同一数据库事务内用共享锁读取栅栏，并验证：

```text
writable = true AND fence.route_epoch = resolvedRoute.route_epoch
```

迁移器冻结时对栅栏执行排他更新。它会等待已开始的写事务结束，随后新的旧路由写入全部返回可重试的 `ROUTE_STALE`。这形成明确的写入切换点。

## 3. 路由缓存

每个应用实例保存最多 100,000 条会话路由，默认 TTL 为 30 秒。控制面每次路由变化都递增 `routing_metadata.route_version`；各实例默认每秒读取该单行，发现版本变化立即清空缓存。因此正常情况下路由收敛不超过一秒，TTL 是版本轮询异常时的第二层保护。

可调参数：

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `IM_ROUTING_MODE` | `control-plane` | `control-plane` 使用动态路由；`static` 仅用于紧急兼容 |
| `IM_ROUTING_CACHE_TTL` | `30s` | 单条缓存有效期 |
| `IM_ROUTING_REVISION_POLL_INTERVAL` | `1s` | 全局版本轮询间隔 |
| `IM_ROUTING_CACHE_MAX_ENTRIES` | `100000` | 单实例缓存上限 |
| `IM_ROUTING_ADMIN_SECRET` | 本地开发值 | 路由管理接口密钥，生产环境必须替换 |
| `IM_ROUTING_MIGRATION_BATCH_SIZE` | `500` | 数据复制批大小 |

跨物理分片迁移时，为目标分片配置连接。应用账号只需要目标业务表的读写权限，不授予 DDL 权限：

```yaml
im:
  routing:
    migration-shards:
      shard-1:
        url: jdbc:mysql://mysql-shard-1:3306/im_message?serverTimezone=UTC&useCursorFetch=true&defaultFetchSize=500
        username: im_migrator
        password: ${IM_SHARD_1_MIGRATION_PASSWORD}
        maximum-pool-size: 4
```

## 4. 在线迁移流程

### 4.1 单会话迁移

1. 锁定控制面路由，确认没有未结束任务。
2. 创建 `migration_jobs`，把会话覆盖路由标为 `MIGRATING`，递增路由版本。
3. 以批处理方式幂等复制会话、成员、消息、附件、Outbox 和发送幂等记录。
4. 把源会话栅栏设为不可写，将源会话 `write_state` 设为迁移中。
5. 再复制一次，追平冻结前刚提交的数据。
6. 比较源目标的消息、成员、附件数量和 `last_seq` 高水位。
7. 将源会话标为已迁出以停止源 Outbox 领取，再准备目标栅栏和新 epoch。
8. 用旧 epoch 作为条件进行路由 CAS 切换并再次校验，任务进入 `COMPLETED`；CAS 失败时恢复源会话。
9. 清理本实例缓存；其他实例通过 `route_version` 自动失效。

热点会话迁移只移动指定会话，不会影响同一槽中的其他会话。

### 4.2 槽位迁移

槽位迁移执行同一状态机，但会移动该槽中没有独立覆盖路由的全部会话。已有会话级路由的热点会话不会随槽位迁移重复移动。

### 4.3 失败处理

- 路由切换前失败：恢复源栅栏和源路由，保留目标端幂等复制的数据，任务标为 `FAILED`，可以修复原因后重新发起。
- 路由切换使用 epoch 条件更新，控制面被其他操作修改时会拒绝切换。
- 路由切换后校验失败：任务保留 `switched=true`，禁止自动切回旧路由；运维人员应修复目标并向前恢复，避免双写。
- 应用不执行建表。所有目标表和发送幂等 generation 必须先由 DBA/Flyway 建好，否则返回 `TARGET_SCHEMA_NOT_READY`。

当前管理接口同步执行迁移，适合单个热点会话和受控的小槽迁移。大槽迁移应在流量低谷执行，并确保网关或负载均衡的管理请求超时大于迁移时长。

## 5. 管理接口

接口只监听应用 HTTP 端口，并要求 `X-IM-Routing-Secret`。生产部署还应在入口层限制为运维网络。

### 查看当前路由

```bash
curl -sS \
  -H 'X-IM-Routing-Secret: local-routing-secret-change-me' \
  'http://127.0.0.1:18080/internal/v1/routing/routes/<conversationId>'
```

### 迁移一个热点会话

目标表组必须与当前表组不同：

```bash
curl -sS -X POST \
  -H 'Content-Type: application/json' \
  -H 'X-IM-Routing-Secret: local-routing-secret-change-me' \
  'http://127.0.0.1:18080/internal/v1/routing/migrations' \
  --data '{
    "scopeType": "CONVERSATION",
    "conversationId": "<conversationId>",
    "targetShard": "shard-0",
    "targetTableGroup": 1
  }'
```

迁移整个槽时传入：

```json
{
  "scopeType": "SLOT",
  "slotId": 31,
  "targetShard": "shard-1",
  "targetTableGroup": 0
}
```

其他接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/internal/v1/routing/migrations/{migrationId}` | 查询迁移结果及校验点 |
| `GET` | `/internal/v1/routing/cache` | 查看本实例缓存条数和路由版本 |
| `POST` | `/internal/v1/routing/cache/invalidate` | 清空本实例路由缓存 |

## 6. 数据库发布

新环境的基础消息库脚本已经包含 `conversation_fences`。已有环境执行：

```bash
mysql -uroot -p < sql/015_stage10_routing_migration_schema.sql
```

同一 MySQL 实例上的本地环境可以启用应用 Flyway：

```bash
IM_FLYWAY_ENABLED=true \
SPRING_FLYWAY_USER='<ddl-account>' \
SPRING_FLYWAY_PASSWORD='<ddl-password>' \
docker compose --profile app up -d app
```

Flyway 使用 `baseline-version=9`，V10 文件与 `sql/015_stage10_routing_migration_schema.sql` 内容一致。Flyway 必须使用独立 DDL 账号；默认业务账号 `im_app` 故意没有建表权限。生产多分片发布时，发布系统应先在控制库执行 V10，再对每个消息分片执行消息库部分；全部成功后才发布应用。迁移账号和业务账号分离，业务账号不需要 `CREATE`、`ALTER`、`DROP` 权限。

## 7. 监控与告警

Prometheus 指标：

| 指标 | 标签 | 用途 |
|---|---|---|
| `im_routing_cache_total` | `result=hit|miss` | 缓存命中率 |
| `im_routing_cache_entries` | 无 | 本实例缓存规模 |
| `im_routing_cache_invalidations_total` | 无 | 缓存失效次数 |
| `im_routing_migrations_total` | `result=completed|failed` | 迁移结果计数 |
| `im_routing_slot_routes` | 无 | 控制面槽位总数，应恒为 1,024 |
| `im_routing_active_migrations` | 无 | 当前非终态迁移数 |
| `im_routing_oldest_active_migration_seconds` | 无 | 最老活动迁移持续时间 |
| `im_routing_metrics_refresh_failures_total` | 无 | 控制面指标刷新失败次数 |

`/actuator/health` 的 `routing` 组件检查 1,024 条槽位路由是否完整，同时报告活动迁移数、缓存条数和观察到的路由版本。

建议告警：

- 5 分钟内出现任何迁移失败。
- `slotRoutes != 1024`。
- 迁移长期停留在非终态。
- 路由缓存 miss 比例持续异常升高。
- `ROUTE_STALE` 在非迁移窗口持续出现。

仓库已提供可直接装载的 Prometheus 规则：`ops/prometheus/im-routing-alerts.yml`。迁移开始、完成和失败会输出包含任务 ID、迁移范围、源目标和校验点的结构化参数日志，不记录管理密钥或数据库密码。

## 8. 验证

先启动完整本地环境，再执行：

```bash
python3 scripts/stage10-routing-migration-smoke-test.py
```

脚本会创建两个临时用户和一个单聊，迁移前发送三条消息，将该会话迁移到另一个表组，然后验证 epoch 增长、历史数据完整，以及迁移后能继续发送第 4 条消息。

Postman 可导入：

- `postman/IM-Local.postman_environment.json`
- `postman/IM-Stage10.postman_collection.json`

