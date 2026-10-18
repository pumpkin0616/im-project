#!/usr/bin/env bash
set -euo pipefail

base_url="${BASE_URL:-http://127.0.0.1:18080}"

health="$(curl -fsS "$base_url/actuator/health/readiness")"
python3 -c 'import json,sys; value=json.load(sys.stdin); assert value["status"]=="UP", value' <<<"$health"
echo "PASS: application readiness is UP"

metrics="$(curl -fsS "$base_url/actuator/prometheus")"
for metric in im_outbox_backlog im_outbox_oldest_unpublished_seconds im_outbox_manual im_gateway_connections_active; do
  grep -q "^${metric} " <<<"$metrics" || { echo "missing metric: $metric" >&2; exit 1; }
done
echo "PASS: gateway and Outbox reliability metrics are exported"

read -r route_count current_generation generation_count manual_events <<<"$(
  docker compose exec -T mysql mysql -uim_app -pim_app -N -e "
    SELECT COUNT(*) FROM im_control.slot_routes;
    SELECT FLOOR(UNIX_TIMESTAMP(UTC_TIMESTAMP())/21600);
    SELECT COUNT(*) FROM im_message.dedup_generations
      WHERE generation BETWEEN FLOOR(UNIX_TIMESTAMP(UTC_TIMESTAMP())/21600)-4
                           AND FLOOR(UNIX_TIMESTAMP(UTC_TIMESTAMP())/21600)+1
        AND state=1 AND accept_until>=UTC_TIMESTAMP(3);
    SELECT (SELECT COUNT(*) FROM im_message.outbox_00 WHERE status=3)
         + (SELECT COUNT(*) FROM im_message.outbox_01 WHERE status=3)
         + (SELECT COUNT(*) FROM im_message.outbox_02 WHERE status=3)
         + (SELECT COUNT(*) FROM im_message.outbox_03 WHERE status=3)
         + (SELECT COUNT(*) FROM im_message.outbox_04 WHERE status=3)
         + (SELECT COUNT(*) FROM im_message.outbox_05 WHERE status=3)
         + (SELECT COUNT(*) FROM im_message.outbox_06 WHERE status=3)
         + (SELECT COUNT(*) FROM im_message.outbox_07 WHERE status=3);" | tr '\n' ' '
)"

[[ "$route_count" == "1024" ]] || { echo "expected 1024 slot routes, got $route_count" >&2; exit 1; }
[[ "$generation_count" == "6" ]] || { echo "generation $current_generation does not have the full retry window" >&2; exit 1; }
[[ "$manual_events" == "0" ]] || { echo "$manual_events Outbox events need manual intervention" >&2; exit 1; }
echo "PASS: routing, rolling idempotency generations and Outbox state are valid"
