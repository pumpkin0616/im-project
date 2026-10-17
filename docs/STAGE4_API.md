# 第四阶段历史消息与附件接口

所有接口要求 `Authorization: Bearer <accessToken>`，数据库时间统一为 UTC。

## 历史消息

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/v1/conversations/{id}/messages?limit=50&beforeSeq=100` | 向更早方向翻页 |
| GET | `/api/v1/conversations/{id}/messages?limit=50&afterSeq=100` | 从断点向更新方向补拉 |
| GET | `/api/v1/conversations/{id}/messages/{seq}` | 按会话序号读取一条消息 |

`beforeSeq` 与 `afterSeq` 互斥，均为排他游标。省略二者时从当前高水位向前读取。响应中的 `items` 始终按 `seq` 升序排列，并携带：

- `highWatermark`：请求时会话已提交的最大序号，客户端用它做补洞和对账。
- `visibleFromSeq`：当前成员可以读取的第一条序号，为 `max(minAvailableSeq, joinedSeq + 1)`。
- `minAvailableSeq`：在线热存储的最小可用序号。
- `nextBeforeSeq`、`nextAfterSeq`：下一次分页可直接使用的排他游标。

历史查询只允许当前有效成员。新成员无法读取入群前消息；查询使用 `(conversation_id, seq)` 索引，不使用 offset 深翻页。

## 会话目录游标

`GET /api/v1/conversations?limit=50&cursor=<opaque>` 返回：

```json
{"items": [], "hasMore": false, "nextCursor": null}
```

游标是服务端生成的不透明字符串，客户端不得解析或拼装。排序键为 `last_activity_at DESC, conversation_id ASC`。

## 附件

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/v1/conversations/{id}/attachments` | 初始化上传 |
| PUT | `/api/v1/conversations/{id}/attachments/{attachmentId}/content` | 上传二进制内容 |
| POST | `/api/v1/conversations/{id}/attachments/{attachmentId}/complete` | 校验大小和 SHA-256，转为 READY |
| GET | `/api/v1/conversations/{id}/attachments/{attachmentId}` | 查询附件元数据 |
| GET | `/api/v1/conversations/{id}/attachments/{attachmentId}/content` | 鉴权下载 |

初始化请求：

```json
{
  "fileName": "hello.txt",
  "mimeType": "text/plain",
  "sizeBytes": 8,
  "sha256": "可选的64位十六进制SHA-256"
}
```

本地环境的 `uploadUrl` 指向应用直传接口，文件存入 Docker 具名卷 `attachment-data`。生产环境可将同一初始化响应切换为私有对象存储预签名 URL，不改变消息发送和下载协议。

附件状态为 `UPLOADING → CHECKING → READY`。只有 READY、属于同一会话、由发送者创建且尚未绑定的附件，才能放入 `POST /api/v1/messages` 的 `attachmentIds`。附件与消息在同一 MySQL 事务内通过 `bound_seq` 绑定。未绑定附件只有上传者可见；绑定后当前会话成员可以下载。

默认单附件上限 25 MiB，上传票据一小时过期。生产环境还需要接入病毒扫描、真实 MIME 检测和 S3/OSS 生命周期清理。

## 本地验收

```bash
docker compose exec -T mysql mysql -uroot -plocal-root < sql/012_stage4_history_attachment_schema.sql
mvn clean package
docker compose --profile app up -d --build app
python3 scripts/stage4-smoke-test.py
```
