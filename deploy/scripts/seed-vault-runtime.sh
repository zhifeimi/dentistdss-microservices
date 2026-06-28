#!/usr/bin/env bash
set -euo pipefail

ENVIRONMENT="${1:-}"
if [[ "${ENVIRONMENT}" != "dev" && "${ENVIRONMENT}" != "prod" ]]; then
  printf 'Usage: %s <dev|prod>\n' "$0" >&2
  exit 2
fi

for command in curl jq openssl awk; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    printf 'Required command not found: %s\n' "${command}" >&2
    exit 1
  fi
done

VAULT_ADDR="${VAULT_ADDR:-https://10.80.0.13:8200}"
HOMENETWORK_ROOT="${HOMENETWORK_ROOT:-${HOME}/Downloads/Projects/homelab/homenetwork}"
VAULT_CA_MANIFEST="${VAULT_CA_MANIFEST:-${HOMENETWORK_ROOT}/k8s/platform/external-secrets/vault-ca-configmap.yaml}"

if [[ ! -f "${VAULT_CA_MANIFEST}" ]]; then
  printf 'Vault CA manifest not found: %s\n' "${VAULT_CA_MANIFEST}" >&2
  exit 1
fi

if [[ -z "${VAULT_TOKEN:-}" ]]; then
  read -r -s -p "Vault token: " VAULT_TOKEN
  printf '\n'
fi

read -r -p "Google OAuth client ID: " GOOGLE_CLIENT_ID
read -r -s -p "Google OAuth client secret: " GOOGLE_CLIENT_SECRET
printf '\n'
read -r -p "SMTP host: " MAIL_HOST
read -r -p "SMTP port [587]: " MAIL_PORT
MAIL_PORT="${MAIL_PORT:-587}"
read -r -p "SMTP username: " MAIL_USERNAME
read -r -s -p "SMTP password: " MAIL_PASSWORD
printf '\n'

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT
umask 077

POSTGRES_PASSWORD="$(openssl rand -hex 32)"
MONGO_PASSWORD="$(openssl rand -hex 32)"
SPRING_CONFIG_PASS="$(openssl rand -hex 32)"
JWT_RSA_KEY_ID="dentistdss-${ENVIRONMENT}-$(date -u +%Y%m%d)"

openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:3072 \
  -out "${TEMP_DIR}/jwt-private.pem" \
  >/dev/null 2>&1
openssl pkey \
  -in "${TEMP_DIR}/jwt-private.pem" \
  -pubout \
  -out "${TEMP_DIR}/jwt-public.pem" \
  >/dev/null 2>&1

JWT_RSA_PRIVATE_KEY="$(
  openssl pkcs8 \
    -topk8 \
    -nocrypt \
    -in "${TEMP_DIR}/jwt-private.pem" \
    -outform DER |
    base64 |
    tr -d '\n'
)"
JWT_RSA_PUBLIC_KEY="$(
  openssl pkey \
    -pubin \
    -in "${TEMP_DIR}/jwt-public.pem" \
    -outform DER |
    base64 |
    tr -d '\n'
)"

awk '
  /-----BEGIN CERTIFICATE-----/ { capture=1 }
  capture {
    sub(/^    /, "")
    print
  }
  /-----END CERTIFICATE-----/ { exit }
' "${VAULT_CA_MANIFEST}" >"${TEMP_DIR}/vault-ca.crt"

jq -n \
  --arg postgres_password "${POSTGRES_PASSWORD}" \
  --arg mongo_password "${MONGO_PASSWORD}" \
  --arg spring_config_user "config-client" \
  --arg spring_config_pass "${SPRING_CONFIG_PASS}" \
  --arg jwt_private "${JWT_RSA_PRIVATE_KEY}" \
  --arg jwt_public "${JWT_RSA_PUBLIC_KEY}" \
  --arg jwt_key_id "${JWT_RSA_KEY_ID}" \
  --arg google_client_id "${GOOGLE_CLIENT_ID}" \
  --arg google_client_secret "${GOOGLE_CLIENT_SECRET}" \
  --arg mail_host "${MAIL_HOST}" \
  --arg mail_port "${MAIL_PORT}" \
  --arg mail_username "${MAIL_USERNAME}" \
  --arg mail_password "${MAIL_PASSWORD}" \
  '{
    data: {
      POSTGRES_PASSWORD: $postgres_password,
      MONGO_INITDB_ROOT_PASSWORD: $mongo_password,
      SPRING_CONFIG_USER: $spring_config_user,
      SPRING_CONFIG_PASS: $spring_config_pass,
      JWT_RSA_PRIVATE_KEY: $jwt_private,
      JWT_RSA_PUBLIC_KEY: $jwt_public,
      JWT_RSA_KEY_ID: $jwt_key_id,
      GOOGLE_CLIENT_ID: $google_client_id,
      GOOGLE_CLIENT_SECRET: $google_client_secret,
      MAIL_HOST: $mail_host,
      MAIL_PORT: $mail_port,
      MAIL_USERNAME: $mail_username,
      MAIL_PASSWORD: $mail_password,
      SPRING_DATA_MONGODB_URI: (
        "mongodb://dentistdss:" + $mongo_password +
        "@mongo:27017/dentistdss?authSource=admin"
      ),
      MONGODB_URI: (
        "mongodb://dentistdss:" + $mongo_password +
        "@mongo:27017/dentistdss_files?authSource=admin"
      )
    }
  }' >"${TEMP_DIR}/payload.json"

printf 'header = "X-Vault-Token: %s"\n' "${VAULT_TOKEN}" >"${TEMP_DIR}/curl.conf"
printf 'header = "Content-Type: application/json"\n' >>"${TEMP_DIR}/curl.conf"

curl \
  --config "${TEMP_DIR}/curl.conf" \
  --fail \
  --silent \
  --show-error \
  --cacert "${TEMP_DIR}/vault-ca.crt" \
  --request POST \
  --data-binary "@${TEMP_DIR}/payload.json" \
  "${VAULT_ADDR}/v1/kv/data/apps/dentistdss/${ENVIRONMENT}/runtime" \
  >/dev/null

printf 'Seeded Vault record kv/apps/dentistdss/%s/runtime.\n' "${ENVIRONMENT}"
