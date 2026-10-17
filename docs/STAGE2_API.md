# 第二阶段认证与好友接口

所有时间使用 UTC。除注册、登录、刷新令牌外，接口均要求：

```http
Authorization: Bearer <accessToken>
```

## 认证与用户

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/v1/auth/register` | 注册并签发令牌 |
| POST | `/api/v1/auth/login` | 登录并创建设备会话 |
| POST | `/api/v1/auth/refresh` | 轮换刷新令牌和访问令牌 |
| POST | `/api/v1/auth/logout` | 撤销刷新令牌 |
| GET | `/api/v1/users/me` | 获取当前用户 |
| PUT | `/api/v1/users/me` | 更新昵称和头像 Key |

注册请求：

```json
{"account":"alice","password":"correct-password","nickname":"Alice","deviceId":"550e8400-e29b-41d4-a716-446655440000"}
```

访问令牌有效期默认 15 分钟，刷新令牌有效期默认 30 天。服务端只保存刷新令牌的 SHA-256 摘要；每次刷新都会废止旧刷新令牌。

## 好友关系

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/v1/friend-requests` | 发起好友申请 |
| GET | `/api/v1/friend-requests?direction=incoming` | 收到的申请 |
| GET | `/api/v1/friend-requests?direction=outgoing` | 发出的申请 |
| POST | `/api/v1/friend-requests/{id}/accept` | 接受申请 |
| POST | `/api/v1/friend-requests/{id}/reject` | 拒绝申请 |
| GET | `/api/v1/friends` | 好友列表 |
| DELETE | `/api/v1/friends/{userId}` | 删除双向好友关系 |
| PUT | `/api/v1/blocks/{userId}` | 拉黑并删除好友关系和待处理申请 |
| DELETE | `/api/v1/blocks/{userId}` | 解除拉黑 |

运行端到端验收：

```bash
python3 scripts/stage2-smoke-test.py
python3 scripts/smoke-test.py
```
