# IM 项目目录结构

## 1. 项目总览

本项目采用单仓库全栈结构，Spring Boot 后端、React Web 客户端、数据库脚本、部署配置、监控规则和压测工具统一维护。

```text
im-project/
├── src/                     Spring Boot 后端
├── web/                     React Web 客户端
├── sql/                     MySQL 初始化及升级脚本
├── infra/                   本地基础设施配置
├── deploy/                  Kubernetes 部署清单
├── ops/                     Prometheus 监控与告警
├── load/                    k6 和 Postman 压测
├── scripts/                 冒烟、故障及运维脚本
├── postman/                 Postman 集合及环境
├── docs/                    产品、架构及阶段文档
├── compose.yaml             本地单实例环境
├── compose.ha.yaml          本地双应用副本环境
├── Dockerfile               Spring Boot 应用镜像
├── pom.xml                  Maven 依赖与构建配置
└── README.md                项目入口文档
```

## 2. Spring Boot 后端

后端代码位于 `src/main`。

```text
src/main/
├── java/com/example/im/
│   ├── ImApplication.java
│   ├── archive/
│   ├── attachment/
│   ├── auth/
│   ├── common/
│   ├── config/
│   ├── conversation/
│   ├── delivery/
│   ├── friend/
│   ├── gateway/
│   ├── message/
│   ├── online/
│   ├── outbox/
│   ├── read/
│   ├── rocketmq/
│   └── routing/
└── resources/
    ├── application.yml
    └── db/migration/
```

### 2.1 业务包职责

| 目录 | 职责 |
|---|---|
| `auth` | 注册、登录、Token、用户资料和头像 |
| `friend` | 用户搜索、好友申请、好友关系、删除和拉黑 |
| `conversation` | 单聊、群聊、群成员、角色和会话列表 |
| `message` | 消息发送、幂等、历史查询、同步和撤回 |
| `attachment` | 图片、文件上传、下载和权限校验 |
| `read` | 已读游标和已读回执 |
| `gateway` | Netty WebSocket、握手、心跳和连接管理 |
| `online` | Redis 在线路由和连接租约 |
| `delivery` | RocketMQ消费、在线投递和目录事件 |
| `outbox` | Outbox发布、重试和监控 |
| `routing` | 1024槽路由、缓存、表组和在线迁移 |
| `archive` | 历史消息归档、Parquet查询和生命周期管理 |
| `config` | 应用集中配置 |
| `common` | 公共异常等基础类型 |

### 2.2 消息模块

消息模块采用接口、应用、领域和基础设施分层。

```text
message/
├── api/                 HTTP接口、请求和响应
├── application/         消息发送用例和消息哈希
├── domain/              领域对象和业务异常
├── infrastructure/      JDBC事务、UUIDv7和去重代次
├── query/               历史查询和离线同步
└── recall/              消息撤回
```

消息发送主要调用链：

```text
MessageController
    ↓
SendMessageHandler
    ↓
JdbcMessageTransactionAdapter
    ├── 校验会话与成员
    ├── 分配会话 seq
    ├── 写入 messages_XX
    ├── 写入幂等记录
    └── 写入 outbox_XX
            ↓
OutboxPublisherWorker
            ↓
RocketMQ
            ↓
RocketMqMessageListener
            ↓
RealtimeDeliveryRouter
            ↓
Netty WebSocket
```

### 2.3 配置与 Flyway

`src/main/resources/application.yml` 管理：

- MySQL和HikariCP
- Redis
- Netty网关
- JWT
- RocketMQ
- Outbox
- 分片路由
- 附件存储
- 历史归档

Flyway增量脚本：

```text
src/main/resources/db/migration/
├── V10__stage10_routing_migration.sql
├── V11__stage11_lifecycle.sql
├── V12__serialize_partition_maintenance.sql
└── V13__rolling_dedup_generations.sql
```

## 3. React Web 客户端

前端位于 `web`。

```text
web/
├── src/
│   ├── main.tsx
│   ├── App.tsx
│   ├── api.ts
│   ├── realtime.ts
│   ├── storage.ts
│   ├── types.ts
│   ├── uuid.ts
│   └── styles.css
├── Dockerfile
├── nginx.conf
├── package.json
├── vite.config.ts
└── tsconfig.json
```

| 文件 | 职责 |
|---|---|
| `main.tsx` | React应用入口 |
| `App.tsx` | 登录、消息、联系人、群聊和个人设置界面 |
| `api.ts` | REST请求、Token刷新和附件请求 |
| `realtime.ts` | WebSocket连接、重连和事件处理 |
| `storage.ts` | 浏览器本地状态与持久化 |
| `types.ts` | API和页面类型定义 |
| `uuid.ts` | UUIDv7等客户端ID生成 |
| `styles.css` | 页面样式和响应式布局 |
| `nginx.conf` | 静态资源及API反向代理 |

`App.tsx` 当前承载较多功能，后续适合按页面、业务组件、Hooks和状态管理继续拆分。

## 4. MySQL 数据库脚本

数据库脚本位于 `sql`。

```text
sql/
├── 001_control_schema.sql
├── 002_message_shard_schema.sql
├── 010_local_fixture.sql
├── 011_stage3_conversation_schema.sql
├── 012_stage4_history_attachment_schema.sql
├── 013_stage5_gateway_schema.sql
├── 014_stage9_product_completion_schema.sql
├── 015_stage10_routing_migration_schema.sql
├── 016_stage11_lifecycle_schema.sql
└── 900_verify_message_correctness.sql
```

| 脚本 | 内容 |
|---|---|
| `001` | 用户、认证、控制库和目录库 |
| `002` | 消息分片表、Outbox、幂等表和槽位栅栏 |
| `010` | 本地联调数据 |
| `011` | 会话和群聊 |
| `012` | 历史消息和附件 |
| `013` | WebSocket Ticket |
| `014` | 好友、头像、撤回和产品闭环字段 |
| `015` | 动态路由和迁移 |
| `016` | 分区、归档和生命周期 |
| `900` | 压测后的数据正确性校验 |

数据库逻辑上分为：

```text
im_control      用户、认证、会话和路由元数据
im_directory    用户会话目录
im_message      分片消息、附件、Outbox和幂等记录
```

## 5. 本地开发基础设施

`compose.yaml` 管理本地单实例环境：

- MySQL 8.4
- Redis 7.4
- RocketMQ 5.3.2 NameServer
- RocketMQ Broker和Proxy
- Spring Boot应用
- React/Nginx Web

`compose.ha.yaml` 增加两个应用实例和Nginx负载均衡，用于本地验证多副本HTTP、WebSocket和故障切换。

```text
infra/
├── mysql/
│   └── 003_local_access.sql
├── nginx/
│   └── ha.conf
└── rocketmq/
    └── broker.conf
```

## 6. Kubernetes 部署

Kubernetes清单位于 `deploy/k8s`。

```text
deploy/k8s/
├── base/
│   ├── app-deployment.yaml
│   ├── app-service.yaml
│   ├── app-hpa.yaml
│   ├── app-pdb.yaml
│   ├── lifecycle-deployment.yaml
│   ├── web-deployment.yaml
│   ├── web-service.yaml
│   ├── ingress.yaml
│   ├── configmap.yaml
│   ├── secret.example.yaml
│   ├── archive-pvc.yaml
│   └── kustomization.yaml
└── canary/
    ├── app-canary.yaml
    ├── service-canary.yaml
    ├── ingress-canary.yaml
    └── kustomization.yaml
```

`base` 提供：

- IM应用3副本
- Web客户端2副本
- HPA 3至12副本
- PDB至少保留2个应用实例
- 跨节点和跨可用区调度
- HTTPS/WSS Ingress
- 生命周期任务独立单副本
- RWX共享存储

`canary` 提供按请求头控制的灰度实例。

当前清单以一个 `shard-0` 应用池为部署基线。完整四分片部署还需要为不同 `IM_LOCAL_SHARD_ID` 建立独立应用池和入口路由。

## 7. 监控和告警

Prometheus规则位于 `ops/prometheus`。

```text
ops/prometheus/
├── im-routing-alerts.yml
├── im-lifecycle-alerts.yml
└── im-slo-alerts.yml
```

- `im-routing-alerts.yml`：路由缓存和迁移异常。
- `im-lifecycle-alerts.yml`：归档和生命周期异常。
- `im-slo-alerts.yml`：API错误率、延迟、Outbox积压和网关拥塞。

## 8. 压测工具

压测工具位于 `load`。

```text
load/
├── k6-send.js
├── k6-websocket.js
├── k6-reconnect.js
├── k6-hot-conversation.js
├── run-stage12.sh
├── run-postman-performance.sh
└── README.md
```

| 文件 | 测试内容 |
|---|---|
| `k6-send.js` | 常态3000条/秒和峰值9000条/秒 |
| `k6-websocket.js` | 1000个同时在线连接 |
| `k6-reconnect.js` | WebSocket重连风暴 |
| `k6-hot-conversation.js` | 热点会话 |
| `run-stage12.sh` | smoke、normal、peak和soak场景 |
| `run-postman-performance.sh` | Postman快速性能回归 |

生成的压测账号和访问令牌保存在 `load/generated`，该目录不会提交到Git。

## 9. 测试与运维脚本

`scripts` 包含：

- 各开发阶段冒烟测试
- 路由迁移测试
- 生命周期测试
- 压测账号生成
- 本地故障演练
- Kubernetes实例故障演练
- Canary灰度发布
- 上线前状态检查

`postman` 保存本地环境以及第9至第12阶段的接口和性能集合。

## 10. 阶段文档

`docs` 中的阶段文档包括：

| 阶段 | 内容 |
|---|---|
| Stage 2 | 认证和好友API |
| Stage 3 | 会话和群聊 |
| Stage 4 | 历史消息和附件 |
| Stage 5 | Netty WebSocket网关 |
| Stage 6 | RocketMQ实时投递 |
| Stage 7 | Web客户端 |
| Stage 8 | 联系人 |
| Stage 9 | 产品功能闭环 |
| Stage 10 | 动态路由与迁移 |
| Stage 11 | 归档与生命周期 |
| Stage 12 | 高可用、压测与灰度上线 |

## 11. 测试目录

后端单元测试位于 `src/test/java`：

```text
src/test/java/com/example/im/
├── message/infrastructure/
│   └── DedupGenerationProvisionerTest.java
└── routing/
    ├── MessageTableNamesTest.java
    └── XxHash64Test.java
```

业务链路主要由 `scripts` 中的阶段冒烟脚本和 `postman` 集合验证。后续应继续增加消息事务、好友关系、群权限、撤回、附件权限和WebSocket事件的自动化测试。

## 12. 后续目录优化建议

1. 将 `web/src/App.tsx` 拆分为页面、组件、Hooks和状态模块。
2. 逐步统一根目录SQL脚本与Flyway迁移的职责。
3. 增加后端集成测试和前端组件、端到端测试目录。
4. 完成四个物理消息分片后，为各分片建立独立Kubernetes Overlay。
5. 将云资源定义放入独立的 `deploy/terraform` 或 `infra/terraform` 目录。
