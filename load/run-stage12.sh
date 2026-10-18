#!/usr/bin/env bash
set -euo pipefail

profile="${1:-smoke}"
: "${IM_IDENTITIES:?set IM_IDENTITIES to a JSON array of load-test conversations and access tokens}"

if command -v k6 >/dev/null 2>&1; then
  exec k6 run -e PROFILE="$profile" -e BASE_URL="${BASE_URL:-http://127.0.0.1:18080}" -e IM_IDENTITIES="$IM_IDENTITIES" load/k6-send.js
fi

base_url="${BASE_URL:-http://host.docker.internal:18080}"
exec docker run --rm -i -v "$PWD:/work" -w /work \
  -e PROFILE="$profile" -e BASE_URL="$base_url" -e IM_IDENTITIES="$IM_IDENTITIES" \
  grafana/k6:0.57.0 run load/k6-send.js
