#!/bin/sh

set -eu

if [ "${1:-}" = "" ]; then
    echo "usage: $0 <secret-path>" >&2
    exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "curl is required" >&2
    exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required" >&2
    exit 1
fi

SECRET_PATH="$1"
BAO_ADDR="${BAO_ADDR:-http://localhost:8200}"
BAO_TOKEN="${BAO_TOKEN:-dev-only-root-token}"

response="$(
    curl -fsSL \
        -H "X-Vault-Token: ${BAO_TOKEN}" \
        "${BAO_ADDR}/v1/secret/data/${SECRET_PATH}"
)"

printf '%s\n' "$response" | jq -r '
    .data.data
    | to_entries[]
    | "export \(.key)=\(.value|@sh)"
'
