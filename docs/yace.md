二十六、怎么测试1000个WebSocket连接
先准备1000个独立用户和设备会话：
python3 scripts/stage12-load-fixtures.py \
  --send-conversations 128 \
  --websocket-users 1000
导入身份数据：
export WS_IDENTITIES="$(cat load/generated/ws-identities.json)"
执行：
k6 run \
  -e WS_IDENTITIES="$WS_IDENTITIES" \
  -e CONNECTIONS=1000 \
  load/k6-websocket.js
脚本会为每个用户：
申请WebSocket Ticket
→ 建立连接
→ 等待READY
→ 每25秒发送PING
→ 保持连接10分钟
脚本位于：
[k6-websocket.js](/Users/xiakaiyan/Documents/code/ai-project/im-project/load/k6-websocket.js)
二十七、怎样测试3000和9000条/秒
准备发送数据：
python3 scripts/stage12-load-fixtures.py \
  --send-conversations 128 \
  --websocket-users 1000
导入：
export IM_IDENTITIES="$(cat load/generated/send-identities.json)"
先跑小流量：
load/run-stage12.sh smoke
再跑常态：
load/run-stage12.sh normal
最后跑峰值：
load/run-stage12.sh peak
也可以连续跑常态和峰值：
load/run-stage12.sh acceptance
发送脚本位于：
[k6-send.js](/Users/xiakaiyan/Documents/code/ai-project/im-project/load/k6-send.js)