#!/bin/sh

set -eu

require_env() {
  name="$1"
  value="$(printenv "$name" 2>/dev/null || true)"
  if [ -z "$value" ]; then
    echo "Missing required environment variable: $name" >&2
    exit 1
  fi
}

require_env BAO_ADDR
require_env BAO_TOKEN

attempts=0
until curl -fsS "$BAO_ADDR/v1/sys/health" >/dev/null 2>&1; do
  attempts=$((attempts + 1))
  if [ "$attempts" -ge 60 ]; then
    echo "OpenBao did not become ready in time" >&2
    exit 1
  fi
  sleep 2
done

find /secrets -type f -name '*.json' | sort | while read -r file; do
  relative_path="${file#/secrets/}"
  secret_path="${relative_path%.json}"

  curl -fsS \
    -H "X-Vault-Token: $BAO_TOKEN" \
    -H "Content-Type: application/json" \
    -X POST \
    --data @"$file" \
    "$BAO_ADDR/v1/secret/data/$secret_path" >/dev/null
done

echo "OpenBao dev secrets initialized"
