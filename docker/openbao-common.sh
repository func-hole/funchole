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

require_bao_token_source() {
  if [ -n "${BAO_TOKEN:-}" ]; then
    return
  fi

  if [ -n "${BAO_TOKEN_FILE:-}" ] && [ -f "${BAO_TOKEN_FILE}" ]; then
    return
  fi

  echo "Missing OpenBao token source. Set BAO_TOKEN or BAO_TOKEN_FILE." >&2
  exit 1
}

load_bao_token() {
  if [ -n "${BAO_TOKEN:-}" ]; then
    return
  fi

  require_bao_token_source
  BAO_TOKEN="$(tr -d '\r\n' < "${BAO_TOKEN_FILE}")"
  if [ -z "${BAO_TOKEN}" ]; then
    echo "OpenBao token file is empty: ${BAO_TOKEN_FILE}" >&2
    exit 1
  fi
  export BAO_TOKEN
}

wait_for_openbao() {
  require_env BAO_ADDR

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
  load_bao_token

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
  load_bao_token

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
