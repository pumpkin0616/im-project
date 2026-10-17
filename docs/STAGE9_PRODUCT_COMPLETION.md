# 阶段九：产品闭环与消息撤回

本阶段补齐消息撤回、会话摘要和未读数、附件消息、个人资料、黑名单、申请取消、群管理及实时目录更新。

## 新增接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/conversations/{conversationId}/messages/{seq}/recall` | 两分钟内撤回自己的消息 |
| DELETE | `/api/v1/friend-requests/{requestId}` | 取消自己发出的待处理申请 |
| GET | `/api/v1/blocks` | 查询黑名单 |
| POST | `/api/v1/users/me/avatar` | 以 `multipart/form-data` 上传头像，字段名为 `file` |
| GET | `/api/v1/users/{userId}/avatar` | 读取头像 |
| DELETE | `/api/v1/users/me/avatar` | 删除头像 |

会话列表和详情增加 `myLastReadSeq`、`unreadCount`、`lastMessage` 和单聊 `peer`。消息模型增加 `status`、`recalledBy` 和 `recalledAt`。

## 撤回保证

- 撤回权限和两分钟窗口使用 MySQL UTC 时间判断。
- 消息状态更新和 `MESSAGE_RECALLED` Outbox 事件在同一事务提交。
- RocketMQ 按 `conversationId` 保持创建与撤回事件顺序。
- 撤回后所有公开查询返回空正文、空附件；原始正文仅在数据库中保留。
- 已撤回消息绑定的附件下载统一返回 404。
- 重复撤回返回第一次撤回的结果，不重复创建事件。

## 本地升级与验证

```bash
docker compose exec -T mysql mysql -uroot -plocal-root < sql/014_stage9_product_completion_schema.sql
mvn package
docker compose --profile app --profile web up -d --build app web
python3 scripts/stage9-product-smoke-test.py
```
