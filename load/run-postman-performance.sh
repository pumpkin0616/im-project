#!/usr/bin/env bash
set -euo pipefail

profile="${1:-smoke}"
collection="postman/IM-Stage12-Performance.postman_collection.json"
environment="${POSTMAN_ENVIRONMENT:-postman/IM-Local.postman_environment.json}"

case "$profile" in
  smoke) args=(--load-profile fixed --peak-vu 5 --duration 1) ;;
  normal) args=(--load-profile fixed --peak-vu 200 --duration 10) ;;
  spike) args=(--load-profile spike --base-vu 20 --peak-vu 500 --duration 5 --load-segments 2m,30s,30s) ;;
  *) echo "usage: $0 smoke|normal|spike" >&2; exit 2 ;;
esac

postman performance run "$collection" --environment "$environment" \
  --runner local --output ndjson "${args[@]}" \
  --pass-if "less_than(p95, 200)" \
  --pass-if "less_than(p99, 1000)" \
  --pass-if "less_than(error_rate, 0.1)" | python3 scripts/verify-postman-performance.py
