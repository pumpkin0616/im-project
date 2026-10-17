# Web IM

面向真实产品的 Web IM 工程，目标负载为常态 3,000 条消息/秒、峰值 9,000 条消息/秒。当前仓库已经落地第一条可靠写入链路：消息服务在一个 MySQL 本地事务中完成会话序号分配、消息写入、请求去重和 Outbox 写入，再由独立工作线程把事件发布到 RocketMQ 5。

完整的需求、容量模型、架构、分片迁移和压测验收标准见 [技术方案](docs/IM_SYSTEM_PRD_AND_TECHNICAL_DESIGN.md)。

## 已实现

- Java 21、Spring Boot 4.1、MySQL 8.4、RocketMQ 5 客户端工程骨架。
- 1,024 个逻辑槽、4 个初始物理分片、每分片 8 个表组的稳定路由。
- UUIDv7 客户端消息号校验及按 6 小时滚动的幂等表。
- 单会话行锁分配严格递增 `seq`，消息、去重记录和 Outbox 同事务提交。
- Outbox 多实例租约抢占、`SKIP LOCKED` 并发读取、失败退避、人工处理阈值。
- RocketMQ 以 `conversationId` 作为 message group，以 `eventId` 作为消费幂等键。
- 可执行的控制库和消息分片建表脚本。
- BCrypt 密码存储、JWT 访问令牌和可轮换的随机刷新令牌。
- 用户注册、登录、刷新、退出和个人资料接口。
- 好友申请、接受、拒绝、好友列表、删除好友及拉黑接口。
- 唯一单聊、群聊创建、会话目录、成员管理和群角色权限。
- 历史消息按会话序号双向游标补拉、成员入群边界和高水位对账。
- 附件初始化、流式上传、SHA-256 校验、消息事务绑定与鉴权下载。
- 会话目录不透明游标分页。
- Netty WebSocket 网关、一次性连接票据、Origin 校验、心跳、限流与慢连接保护。
- WebSocket `SEND` 复用可靠消息事务，并支持同设备连接替换。
- Redis 在线连接租约、条件续租/删除和按用户批量路由查询。
- RocketMQ `MESSAGE_CREATED` 消费、在线 `MESSAGE` 推送和跨网关批量 Dispatch。
- 批量离线同步、WebSocket/HTTP 已读游标更新和单聊已读回执。
- React + TypeScript Web 客户端、IndexedDB 消息缓存、断线重连和端到端状态机。

## 本地构建

```bash
mvn test
mvn package
```

应用默认连接本机 `im_message` 数据库，默认关闭 RocketMQ 发布器。关键环境变量：

| 变量 | 默认值 | 说明 |
|---|---:|---|
| `IM_DB_URL` | `jdbc:mysql://127.0.0.1:3307/im_message...` | 当前消息分片地址 |
| `IM_DB_USERNAME` | `im_app` | 数据库用户 |
| `IM_DB_PASSWORD` | `im_app` | 数据库密码 |
| `IM_LOCAL_SHARD_ID` | `shard-0` | 本实例负责的物理分片 |
| `IM_ROCKETMQ_ENABLED` | `false` | 是否启动 Outbox 发布器 |
| `IM_ROCKETMQ_ENDPOINTS` | `127.0.0.1:8081` | RocketMQ Proxy gRPC 地址 |
| `IM_ROCKETMQ_MESSAGE_TOPIC` | `im-message-events` | 消息事件 Topic |
| `IM_OUTBOX_MAX_IN_FLIGHT` | `2000` | 单实例异步发布上限，用于背压 |
| `IM_GATEWAY_PORT` | `18081` | Netty WebSocket 监听端口 |
| `IM_GATEWAY_PUBLIC_URL` | `ws://127.0.0.1:18081/ws` | 返回给客户端的连接地址 |
| `IM_GATEWAY_ALLOWED_ORIGINS` | 本地 Vite 地址 | 允许建立连接的浏览器来源 |
| `IM_REDIS_HOST` / `IM_REDIS_PORT` | `127.0.0.1:6380` | 在线路由 Redis |
| `IM_GATEWAY_ID` | `gateway-local-1` | 网关实例唯一标识 |
| `IM_GATEWAY_DISPATCH_URL` | 本机内部地址 | 其他网关访问当前实例的地址 |
| `IM_ROCKETMQ_CONSUMER_GROUP` | `im-realtime-delivery-v1` | 实时投递消费组 |

## 初始化数据库

本地开发推荐直接启动依赖：

```bash
docker compose up -d
docker compose ps
```

Compose 会启动 MySQL 8.4、RocketMQ 5.3.2 的 NameServer、Broker 与 Proxy，初始化数据库并创建 `im-message-events` Topic。数据保存在具名卷中；需要完全重建时执行 `docker compose down -v` 后重新启动。

构建 JAR 后，可用固定的 Java 21 运行时启动应用：

```bash
mvn package
docker compose --profile app up -d --build app
```

容器化应用监听宿主机 `http://127.0.0.1:18080`，以免与常见的 8080 开发服务冲突。

需要本地联调消息发送接口时，可装载固定会话和成员：

```bash
docker compose exec -T mysql mysql -uroot -plocal-root < sql/010_local_fixture.sql
```

随后运行一键冒烟测试。它会检查服务健康、首次发送、相同请求重放和冲突请求拒绝：

```bash
python3 scripts/smoke-test.py
```

`rocketmq-store-init` 和 `rocketmq-init` 是一次性初始化任务。它们显示
`Exited (0)` 代表权限初始化和 Topic 创建成功；需要持续显示 `healthy` 的服务是
`mysql`、`namesrv`、`broker` 和 `app`。

控制库执行一次；消息库脚本需要在四个物理消息分片分别执行：

```bash
mysql -uroot -p < sql/001_control_schema.sql
mysql -uroot -p < sql/002_message_shard_schema.sql
```

随后在每个物理分片初始化它负责的槽位，参数依次为 `0`、`1`、`2`、`3`：

```sql
CALL im_message.provision_shard_slots(0);
```

消息库脚本会创建 8 个表组，并预建覆盖最近 24 小时、当前窗口和下一个窗口的去重表。生产环境需要每 6 小时提前执行：

```sql
CALL im_message.provision_dedup_generation(
  FLOOR(UNIX_TIMESTAMP(UTC_TIMESTAMP()) / 21600) + 1
);
```

## 启动

```bash
IM_LOCAL_SHARD_ID=shard-0 \
IM_DB_URL='jdbc:mysql://127.0.0.1:3307/im_message?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC' \
mvn spring-boot:run
```

发送接口为 `POST /api/v1/messages`，需要携带登录接口签发的 `Authorization: Bearer <accessToken>`。请求中的 `clientMsgId` 必须是 UUIDv7，服务端会拒绝早于 24 小时或超前超过 5 分钟的值。认证与好友接口见 [第二阶段文档](docs/STAGE2_API.md)，会话与群组接口见 [第三阶段文档](docs/STAGE3_API.md)，历史消息和附件接口见 [第四阶段文档](docs/STAGE4_API.md)，实时连接协议见 [第五阶段文档](docs/STAGE5_WEBSOCKET_GATEWAY.md)，实时投递与同步见 [第六阶段文档](docs/STAGE6_REALTIME_DELIVERY.md)。

## 当前边界与下一阶段

当前已完成可靠消息写入、用户认证、好友关系、会话与成员权限、历史消息、附件、Netty WebSocket 网关、Redis 在线租约、RocketMQ 实时投递、跨网关 Dispatch、离线同步、已读回执，以及 React Web 客户端。Web 使用与启动方式见 [第七阶段文档](docs/STAGE7_WEB_CLIENT.md)。下一阶段完成目录事件投影、路由迁移自动化与 3,000/9,000 条每秒压测。正式部署前还要把本地文件附件适配器替换为私有对象存储，并把初始静态路由替换为控制库路由快照。



首次完整启动
cd /Users/xiakaiyan/Documents/code/ai-project/im-project

mvn clean package

docker compose --profile app --profile web up -d --build

docker compose exec -T mysql \
  mysql -uroot -plocal-root \
  < sql/010_local_fixture.sql

docker compose --profile app ps

# Web 页面：http://127.0.0.1:3000

python3 scripts/smoke-test.py
