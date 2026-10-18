#!/usr/bin/env bash
set -euo pipefail

: "${KUBE_CONTEXT:?set KUBE_CONTEXT to the reviewed production context}"
: "${IMAGE:?set IMAGE to an immutable application image, preferably by digest}"
: "${BASE_URL:?set BASE_URL, for example https://im.example.com}"
: "${ACCESS_TOKEN:?set ACCESS_TOKEN for the canary test account}"
: "${CONVERSATION_ID:?set CONVERSATION_ID for the canary test conversation}"

NAMESPACE="${NAMESPACE:-im}"
CANARY_DEPLOYMENT="im-app-canary"
STABLE_DEPLOYMENT="im-app"

kubectl --context "$KUBE_CONTEXT" -n "$NAMESPACE" set image \
  "deployment/$CANARY_DEPLOYMENT" "app=$IMAGE"
kubectl --context "$KUBE_CONTEXT" -n "$NAMESPACE" rollout status \
  "deployment/$CANARY_DEPLOYMENT" --timeout=5m

client_msg_id="$(python3 - <<'PY'
import secrets,time
v=(int(time.time()*1000)<<80)|(7<<76)|(secrets.randbits(12)<<64)|(2<<62)|secrets.randbits(62)
s=f'{v:032x}'; print(f'{s[:8]}-{s[8:12]}-{s[12:16]}-{s[16:20]}-{s[20:]}')
PY
)"

curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/messages" \
  -H 'Content-Type: application/json' \
  -H 'X-IM-Canary: always' \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --data "{\"conversationId\":\"$CONVERSATION_ID\",\"clientMsgId\":\"$client_msg_id\",\"type\":\"TEXT\",\"content\":{\"text\":\"canary verification\"},\"attachmentIds\":[]}" >/dev/null

kubectl --context "$KUBE_CONTEXT" -n "$NAMESPACE" set image \
  "deployment/$STABLE_DEPLOYMENT" "app=$IMAGE"
kubectl --context "$KUBE_CONTEXT" -n "$NAMESPACE" rollout status \
  "deployment/$STABLE_DEPLOYMENT" --timeout=10m

echo "Canary request and stable rollout completed. Keep the canary for observation or scale it to zero after the monitoring window."
