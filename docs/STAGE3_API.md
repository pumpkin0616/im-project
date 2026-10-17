# 第三阶段会话与群组接口

所有接口要求 `Authorization: Bearer <accessToken>`。

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/v1/conversations/direct` | 创建或返回两人唯一单聊 |
| POST | `/api/v1/conversations/group` | 创建群聊，当前用户成为群主 |
| GET | `/api/v1/conversations` | 查询当前用户的会话目录 |
| GET | `/api/v1/conversations/{id}` | 查询会话和有效成员 |
| PATCH | `/api/v1/conversations/{id}` | 群主或管理员修改群名 |
| POST | `/api/v1/conversations/{id}/members` | 群主或管理员添加成员 |
| DELETE | `/api/v1/conversations/{id}/members/{userId}` | 移除群成员 |
| PUT | `/api/v1/conversations/{id}/members/{userId}/role` | 群主设置管理员或普通成员 |
| POST | `/api/v1/conversations/{id}/leave` | 当前用户退出群聊 |
| POST | `/api/v1/conversations/{id}/dissolve` | 群主解散群聊 |

单聊要求双方已经是好友。同一对用户并发创建单聊时，`direct_key` 全局唯一约束保证最终只产生一个会话。

群聊角色：`MEMBER`、`ADMIN`、`OWNER`。管理员不能移除其他管理员或群主；群主不能直接退群，需要先转让群主（后续接口）或解散群聊。

会话和成员变更与业务数据在同一事务中写入 Outbox，并以 `conversationId` 为 Message Group 发布到32队列的 FIFO Topic `im-membership-events`。本地环境同步维护用户会话目录；将目录库拆为独立实例后，可由目录消费者按 `memberVersion` 幂等更新。

运行验收：

```bash
docker compose exec -T mysql mysql -uroot -plocal-root < sql/011_stage3_conversation_schema.sql
python3 scripts/stage3-smoke-test.py
```
