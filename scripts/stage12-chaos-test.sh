#!/usr/bin/env bash
set -euo pipefail

[[ "${ALLOW_LOCAL_CHAOS:-}" == "1" ]] || {
  echo "Set ALLOW_LOCAL_CHAOS=1 to confirm this is the disposable local Compose environment." >&2
  exit 2
}

recover() {
  docker compose unpause redis broker >/dev/null 2>&1 || true
  docker compose --profile app --profile web up -d mysql redis namesrv broker app web >/dev/null 2>&1 || true
}
trap recover EXIT INT TERM

wait_ready() {
  for _ in $(seq 1 60); do
    curl -fsS http://127.0.0.1:18080/actuator/health/readiness 2>/dev/null | grep -q '"status":"UP"' && return 0
    sleep 2
  done
  echo "application did not recover readiness" >&2
  return 1
}

echo "[1/4] application restart"
docker compose restart app >/dev/null
wait_ready
python3 scripts/smoke-test.py >/dev/null
echo "PASS: application restart preserved message writes"

echo "[2/4] Redis interruption"
docker compose pause redis >/dev/null
python3 scripts/smoke-test.py >/dev/null
docker compose unpause redis >/dev/null
echo "PASS: Redis interruption did not stop durable message writes"

echo "[3/4] RocketMQ interruption"
docker compose pause broker >/dev/null
python3 scripts/smoke-test.py >/dev/null
backlog="0"
for _ in $(seq 1 15); do
  backlog="$(curl -fsS http://127.0.0.1:18080/actuator/prometheus | awk '/^im_outbox_backlog / {print $2}')"
  [[ "$backlog" != "0.0" && "$backlog" != "0" ]] && break
  sleep 1
done
[[ "$backlog" != "0.0" && "$backlog" != "0" ]] || { echo "Outbox backlog did not become visible during broker outage" >&2; exit 1; }
docker compose unpause broker >/dev/null
for _ in $(seq 1 90); do
  backlog="$(curl -fsS http://127.0.0.1:18080/actuator/prometheus | awk '/^im_outbox_backlog / {print $2}')"
  [[ "$backlog" == "0.0" || "$backlog" == "0" ]] && break
  sleep 2
done
[[ "$backlog" == "0.0" || "$backlog" == "0" ]] || { echo "Outbox did not drain: $backlog" >&2; exit 1; }
echo "PASS: RocketMQ outage was buffered by Outbox and drained after recovery"

echo "[4/4] MySQL interruption"
docker compose stop mysql >/dev/null
unready=0
for _ in $(seq 1 30); do
  if ! curl -fsS --max-time 3 http://127.0.0.1:18080/actuator/health/readiness 2>/dev/null | grep -q '"status":"UP"'; then
    unready=1
    break
  fi
  sleep 1
done
[[ "$unready" == "1" ]] || { echo "readiness stayed UP while MySQL was unavailable" >&2; exit 1; }
docker compose start mysql >/dev/null
wait_ready
python3 scripts/smoke-test.py >/dev/null
echo "PASS: readiness rejected traffic during MySQL outage and recovered afterward"
