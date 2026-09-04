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

json_bool_field() {
  field_name="$1"
  response="$2"
  printf '%s' "$response" \
    | tr -d '\n' \
    | sed -n "s/.*\"${field_name}\"[[:space:]]*:[[:space:]]*\\(true\\|false\\).*/\\1/p"
}

json_string_field() {
  field_name="$1"
  response="$2"
  printf '%s' "$response" \
    | tr -d '\n' \
    | sed -n "s/.*\"${field_name}\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p"
}

json_first_array_string() {
  field_name="$1"
  response="$2"
  printf '%s' "$response" \
    | tr -d '\n' \
    | sed -n "s/.*\"${field_name}\"[[:space:]]*:[[:space:]]*\\[[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p"
}

require_env BAO_ADDR
OPENBAO_STATE_DIR="${OPENBAO_STATE_DIR:-/openbao/bootstrap}"
ROOT_TOKEN_FILE="${OPENBAO_STATE_DIR}/root-token"
UNSEAL_KEY_FILE="${OPENBAO_STATE_DIR}/unseal-key"

mkdir -p "${OPENBAO_STATE_DIR}"

attempts=0
until curl -sS "$BAO_ADDR/v1/sys/init" >/dev/null 2>&1; do
  attempts=$((attempts + 1))
  if [ "$attempts" -ge 60 ]; then
    echo "OpenBao did not become ready in time" >&2
    exit 1
  fi
  sleep 2
done

init_status_json="$(curl -fsS "$BAO_ADDR/v1/sys/init")"
initialized="$(json_bool_field initialized "$init_status_json")"

if [ "$initialized" != "true" ]; then
  init_json="$(curl -fsS \
    -X PUT \
    -H "Content-Type: application/json" \
    --data '{"secret_shares":1,"secret_threshold":1}' \
    "$BAO_ADDR/v1/sys/init")"
  json_string_field root_token "$init_json" > "$ROOT_TOKEN_FILE"
  json_first_array_string keys_base64 "$init_json" > "$UNSEAL_KEY_FILE"
  chmod 600 "$ROOT_TOKEN_FILE" "$UNSEAL_KEY_FILE"
fi

if [ ! -f "$ROOT_TOKEN_FILE" ] || [ ! -f "$UNSEAL_KEY_FILE" ]; then
  echo "OpenBao bootstrap files are missing under $OPENBAO_STATE_DIR" >&2
  exit 1
fi

seal_status_json="$(curl -fsS "$BAO_ADDR/v1/sys/seal-status")"
seal_status="$(json_bool_field sealed "$seal_status_json")"
if [ "$seal_status" = "true" ]; then
  curl -fsS \
    -X PUT \
    -H "Content-Type: application/json" \
    --data "{\"key\":\"$(cat "$UNSEAL_KEY_FILE")\"}" \
    "$BAO_ADDR/v1/sys/unseal" >/dev/null
fi

export BAO_TOKEN="$(tr -d '\r\n' < "$ROOT_TOKEN_FILE")"

mounts_json="$(curl -fsS \
  -H "X-Vault-Token: $BAO_TOKEN" \
  "$BAO_ADDR/v1/sys/mounts")"
if ! printf '%s' "$mounts_json" | tr -d '\n' | grep -q '"secret/"'; then
  curl -fsS \
    -X POST \
    -H "X-Vault-Token: $BAO_TOKEN" \
    -H "Content-Type: application/json" \
    --data '{"type":"kv","options":{"version":"2"}}' \
    "$BAO_ADDR/v1/sys/mounts/secret" >/dev/null
fi

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

echo "OpenBao secrets initialized"
