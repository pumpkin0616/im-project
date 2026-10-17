# 第七阶段：Web 客户端与端到端状态机

第七阶段新增 `web/` React + TypeScript 客户端，覆盖登录注册、会话目录、历史消息、实时收发、离线恢复、已读推进和响应式移动布局。生产构建由 Nginx 提供静态文件，并把 `/api` 代理到 Spring Boot；WebSocket 使用后端签发的一次性票据直连 Netty 网关。

## 页面能力

- 登录、注册、刷新令牌恢复和退出。
- 会话搜索、单聊创建、群聊创建和成员摘要。
- 最近历史加载、向前分页及 IndexedDB 最近 500 条消息缓存。
- WebSocket `READY`、`MESSAGE`、`SEND_ACK`、`READ_UPDATE`、`ERROR`、`RECONNECT` 状态处理。
- 发送消息先显示本地气泡；连接在线时走 WebSocket，离线时自动走可靠 HTTP 发送接口。
- 按 `messageId` 和 `clientMsgId` 合并消息，解决 ACK、MQ 推送和重连补拉的重复与乱序到达。
- 连接断开后使用带抖动的指数退避重连；重新 `READY` 后按每个会话最高 `seq` 批量同步，并持续分页直到追上高水位。
- 浏览当前会话时自动单调推进已读游标。
- 桌面双栏布局与手机会话/消息双页面布局。

## 客户端状态

| 状态 | 来源 | 用途 |
|---|---|---|
| 身份会话 | 内存访问令牌、浏览器刷新令牌和稳定 `deviceId` | 自动恢复登录和多设备身份 |
| 会话目录 | `GET /api/v1/conversations` | 导航及会话高水位 |
| 本地消息 | IndexedDB，每会话最多 500 条 | 快速首屏及短时离线浏览 |
| 历史消息 | `GET .../messages`、`POST /api/v1/sync` | 权威补拉和缺口恢复 |
| 实时消息 | Netty WebSocket | 低延迟消息、ACK 和已读事件 |
| 发送中消息 | `clientMsgId` 关联的内存状态 | 乐观显示、ACK 替换和失败提示 |

服务端消息记录和会话序号是权威数据。IndexedDB 可以清除和重建；MQ 重复投递不会产生重复气泡。

## 本地开发

后端保持运行后：

```bash
cd web
npm install
npm run dev
```

打开 `http://127.0.0.1:3000`。Vite 把 `/api` 代理到 `http://127.0.0.1:18080`，浏览器从后端获得 `ws://127.0.0.1:18081/ws` 连接地址。

## 容器启动

在项目根目录执行：

```bash
mvn package
docker compose --profile app --profile web up -d --build
docker compose --profile app --profile web ps
```

访问地址：

- Web：`http://127.0.0.1:3000`
- HTTP API：`http://127.0.0.1:18080`
- WebSocket：`ws://127.0.0.1:18081/ws`

正式部署时需要把 `IM_GATEWAY_PUBLIC_URL` 设置为浏览器能够访问的 WSS 地址，并把 Web 站点 Origin 加入 `IM_GATEWAY_ALLOWED_ORIGINS`。HTTPS 页面必须连接 `wss://`，不能连接明文 `ws://`。

## 验证

```bash
cd web
npm audit --registry=https://registry.npmjs.org
npm run build
cd ..
python3 scripts/stage7-smoke-test.py
python3 scripts/stage6-smoke-test.py
```

第七阶段脚本检查生产静态资源、SPA 回退、Nginx API 代理、认证会话和 WebSocket 票据；第六阶段脚本继续验证真实的双端 WebSocket 投递、断线同步和已读回执。

## 后续扩展

附件消息、群管理、个人资料、目录摘要和消息撤回已在[第九阶段](STAGE9_PRODUCT_COMPLETION.md)完成。浏览器通知和 Service Worker 离线壳仍可继续补充。生产认证建议把刷新令牌改为 `HttpOnly + Secure + SameSite` Cookie，进一步缩小 XSS 场景下的令牌暴露面。
