# 第十一阶段：历史归档与数据生命周期

## 1. 交付范围

- MySQL 消息日分区提前 14 天自动创建。
- 已关闭 UTC 日期按“分片、表组、槽、日期、generation”归档。
- Parquet + ZSTD 冷存储，文件内部按会话序号排序。
- 文件 SHA-256、文件行数、源数据行数和序号范围校验。
- `PREPARING → UPLOADED → VERIFIED → PUBLISHED → HOT_DROPPED` 状态机。
- 发布清单后才分批删除 MySQL 热消息。
- 历史列表、单条消息和离线同步透明合并冷热数据。
- Outbox、发送幂等记录、过期附件和失败上传清理。
- 启动时及每 5 分钟滚动创建完整重试窗口和下一窗口的发送幂等分代表。
- 归档失败恢复、运行审计、指标和告警。

## 2. 存储布局

本地环境使用 Docker volume，生产环境可把相同对象键映射到私有 S3/OSS：

```text
archive/v1/shard=shard-0/group=07/slot=0234/day=2026-10-16/generation=1/
  conversation=<uuid>/part-00000.parquet
  manifest.json
```

Parquet 使用 ZSTD 压缩。撤回前正文保留在受保护的归档文件中用于内部审计，但公共查询读取到 `RECALLED` 时仍强制返回空正文和空附件。

清单记录批次、槽、日期、总行数，以及各会话文件的 `minSeq`、`maxSeq`、行数、字节数和 SHA-256。`archive_segments_XX` 保存查询索引，历史接口无需扫描对象目录。

## 3. 安全状态机

1. 确认日期已结束、路由没有迁移、Outbox 没有待发布事件。
2. 创建不可变 generation 和 `PREPARING` 批次。
3. 使用 JDBC 游标读取热消息并写入临时 Parquet。
4. 计算 SHA-256，重新读取 Parquet 校验行数，再原子发布对象。
5. 写入并校验 `manifest.json`，批次进入 `VERIFIED`。
6. 在 MySQL 事务中写入段索引，将批次设为 `PUBLISHED`。
7. 按会话分批删除热消息，将批次设为 `HOT_DROPPED`。

`PUBLISHED` 之前失败时，批次标记为 `FAILED`，热数据不会删除；修复后使用新 generation。发布后若热删除中断，冷读已经可用，批次保持 `PUBLISHED`。再次提交同一范围会继续删除剩余热数据。

归档与槽迁移、热点会话迁移互斥。迁移后的旧源副本 `write_state=3` 不参与归档。
路由迁移会同步复制归档批次和段索引；所有物理消息分片必须访问同一个归档对象命名空间。跨分片生产部署不能为每个分片配置彼此隔离的本地目录。

## 4. 冷热统一查询

现有接口及响应模型保持不变：

```http
GET  /api/v1/conversations/{conversationId}/messages
GET  /api/v1/conversations/{conversationId}/messages/{seq}
POST /api/v1/sync/messages
```

查询同时读取 MySQL 和已发布归档段，以 `seq` 去重并排序。冷热数据暂时重叠时也只返回一条消息。成员 `joined_seq`、会话高水位和撤回脱敏规则继续生效。

附件元数据保留在 `attachments_XX`，冷消息可以继续鉴权下载附件。达到附件保留期限后，清理任务同时删除文件和元数据，历史消息不再返回该附件。

## 5. 生命周期策略

| 配置 | 默认值 | 作用 |
|---|---:|---|
| `IM_ARCHIVE_ENABLED` | `false` | 自动归档和清理调度开关 |
| `IM_ARCHIVE_ROOT` | `./data/archive` | 冷归档对象根目录 |
| `IM_ARCHIVE_HOT_RETENTION` | `7d` | MySQL 热消息保留时间 |
| `IM_OUTBOX_RETENTION` | `7d` | 已发布 Outbox 保留时间 |
| `IM_ATTACHMENT_RETENTION` | `180d` | 已绑定附件保留时间 |
| `IM_FAILED_UPLOAD_RETENTION` | `1d` | 失败或未完成上传保留时间 |
| `IM_LIFECYCLE_BATCH_SIZE` | `1000` | 删除和归档批量 |
| `IM_PARTITION_AHEAD_DAYS` | `14` | 提前创建分区天数 |
| `IM_LIFECYCLE_ADMIN_SECRET` | 本地开发值 | 管理接口密钥 |
| `IM_DEDUP_PROVISION_INTERVAL_MS` | `300000` | 幂等分代滚动创建间隔 |

发送幂等记录至少保留自动重试窗口，过期 generation 的数据会被清空。表 DDL 删除交给发布系统，业务账号不具有任意 `DROP TABLE` 权限。

## 6. 运维接口

所有接口要求 `X-IM-Lifecycle-Secret`，生产入口还应限制为运维网络。

```bash
curl -sS -X POST 'http://127.0.0.1:18080/internal/v1/lifecycle/archives' \
  -H 'Content-Type: application/json' \
  -H 'X-IM-Lifecycle-Secret: local-lifecycle-secret-change-me' \
  --data '{"tableGroup":7,"slotId":234,"messageDay":"2026-10-16"}'

curl -i -X POST \
  -H 'X-IM-Lifecycle-Secret: local-lifecycle-secret-change-me' \
  'http://127.0.0.1:18080/internal/v1/lifecycle/partitions/ensure'

curl -sS -X POST \
  -H 'X-IM-Lifecycle-Secret: local-lifecycle-secret-change-me' \
  'http://127.0.0.1:18080/internal/v1/lifecycle/cleanup'
```

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/internal/v1/lifecycle/status` | 查看开关、积压和失败任务 |
| `GET` | `/internal/v1/lifecycle/archives/{tableGroup}/{batchId}` | 查询批次和校验结果 |

## 7. 数据库发布

每个物理消息分片执行：

```bash
mysql -uroot -p < sql/016_stage11_lifecycle_schema.sql
```

Flyway 使用 V11 创建归档结构，并由 V12 为分区维护增加 MySQL advisory lock，避免多副本同时执行 DDL。生产由 DDL 账号执行，再单独把该过程的 `EXECUTE` 权限授予业务账号：

```sql
GRANT EXECUTE ON PROCEDURE im_message.ensure_message_partitions TO 'im_app'@'%';
GRANT EXECUTE ON PROCEDURE im_message.provision_dedup_generation TO 'im_app'@'%';
```

## 8. 监控与验证

| 指标 | 说明 |
|---|---|
| `im_lifecycle_archives_total{result}` | 归档成功/失败数 |
| `im_lifecycle_archive_backlog` | 超过热保留期但尚未归档的槽日数 |
| `im_lifecycle_cleanup_total{result}` | 清理成功/失败数 |
| `im_lifecycle_archive_failed_batches` | 数据库中尚未处理的失败批次数 |
| `im_lifecycle_archive_oldest_active_seconds` | 最老非终态批次停留时间 |

告警规则位于 `ops/prometheus/im-lifecycle-alerts.yml`。

```bash
python3 scripts/stage11-lifecycle-smoke-test.py
```

脚本验证 Parquet 发布、热数据删除、冷热历史读取、管理鉴权、未来分区创建和 Outbox 清理。Postman 可导入 `postman/IM-Local.postman_environment.json` 与 `postman/IM-Stage11.postman_collection.json`。

