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

wait_for_openbao() {
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
}

fetch_secret_field() {
  secret_path="$1"
  field_name="$2"

  response="$(curl -fsS \
    -H "X-Vault-Token: $BAO_TOKEN" \
    "$BAO_ADDR/v1/secret/data/$secret_path")"

  value="$(printf '%s' "$response" | jq -r ".data.data.\"$field_name\" // empty")"
  if [ -z "$value" ] || [ "$value" = "null" ]; then
    echo "Missing field '$field_name' in OpenBao secret '$secret_path'" >&2
    exit 1
  fi

  printf '%s' "$value"
}

export_secret_document() {
  secret_path="$1"

  response="$(curl -fsS \
    -H "X-Vault-Token: $BAO_TOKEN" \
    "$BAO_ADDR/v1/secret/data/$secret_path")"

  exports="$(printf '%s' "$response" | jq -r '
    .data.data
    | to_entries[]
    | "export \(.key)=\(.value | tostring | @sh)"
  ')"

  if [ -z "$exports" ]; then
    echo "No environment values found in OpenBao secret '$secret_path'" >&2
    exit 1
  fi

  eval "$exports"
}
