#!/usr/bin/env bash
# seed-vault-db-credentials.sh — DATA-02: seed the per-service database
# credential records (kv/apps/dentistdss/<env>/db-credentials/<service>)
# without touching the runtime record, so rolling out per-service credentials
# on an existing environment does NOT rotate JWT keys or root passwords.
#
# Generates fresh random passwords for:
#   PostgreSQL roles: svc_auth, svc_clinic, svc_user_profile, svc_appointment,
#     svc_clinical_records, svc_notification, svc_system
#   MongoDB users: svc_audit, svc_genai (collection-scoped on db dentistdss),
#     svc_clinical_records (readWrite on dentistdss_files)
#
# After seeding, apply the SAME passwords to the databases with
# deploy/scripts/provision-db-roles.sh (read the values back out of Vault;
# they are never written to disk here), then upgrade the chart.
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

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT
umask 077

AUTH_SERVICE_DB_PASSWORD="$(openssl rand -hex 32)"
CLINIC_SERVICE_DB_PASSWORD="$(openssl rand -hex 32)"
USER_PROFILE_SERVICE_DB_PASSWORD="$(openssl rand -hex 32)"
APPOINTMENT_SERVICE_DB_PASSWORD="$(openssl rand -hex 32)"
CLINICAL_RECORDS_SERVICE_DB_PASSWORD="$(openssl rand -hex 32)"
NOTIFICATION_SERVICE_DB_PASSWORD="$(openssl rand -hex 32)"
SYSTEM_SERVICE_DB_PASSWORD="$(openssl rand -hex 32)"
AUDIT_SERVICE_MONGO_PASSWORD="$(openssl rand -hex 32)"
GENAI_SERVICE_MONGO_PASSWORD="$(openssl rand -hex 32)"
CLINICAL_RECORDS_SERVICE_MONGO_PASSWORD="$(openssl rand -hex 32)"

awk '
  /-----BEGIN CERTIFICATE-----/ { capture=1 }
  capture {
    sub(/^    /, "")
    print
  }
  /-----END CERTIFICATE-----/ { exit }
' "${VAULT_CA_MANIFEST}" >"${TEMP_DIR}/vault-ca.crt"

printf 'header = "X-Vault-Token: %s"\n' "${VAULT_TOKEN}" >"${TEMP_DIR}/curl.conf"
printf 'header = "Content-Type: application/json"\n' >>"${TEMP_DIR}/curl.conf"

seed_record() {
  local service="$1" payload="$2"
  printf '%s' "${payload}" >"${TEMP_DIR}/db-payload.json"
  curl \
    --config "${TEMP_DIR}/curl.conf" \
    --fail \
    --silent \
    --show-error \
    --cacert "${TEMP_DIR}/vault-ca.crt" \
    --request POST \
    --data-binary "@${TEMP_DIR}/db-payload.json" \
    "${VAULT_ADDR}/v1/kv/data/apps/dentistdss/${ENVIRONMENT}/db-credentials/${service}" \
    >/dev/null
  printf 'Seeded Vault record kv/apps/dentistdss/%s/db-credentials/%s.\n' "${ENVIRONMENT}" "${service}"
}

pg_record() {
  jq -n --arg u "$1" --arg p "$2" '{data: {DB_USERNAME: $u, DB_PASSWORD: $p}}'
}

seed_record auth               "$(pg_record svc_auth "${AUTH_SERVICE_DB_PASSWORD}")"
seed_record clinic             "$(pg_record svc_clinic "${CLINIC_SERVICE_DB_PASSWORD}")"
seed_record user-profile       "$(pg_record svc_user_profile "${USER_PROFILE_SERVICE_DB_PASSWORD}")"
seed_record appointment        "$(pg_record svc_appointment "${APPOINTMENT_SERVICE_DB_PASSWORD}")"
seed_record notification       "$(pg_record svc_notification "${NOTIFICATION_SERVICE_DB_PASSWORD}")"
seed_record system             "$(pg_record svc_system "${SYSTEM_SERVICE_DB_PASSWORD}")"
seed_record clinical-records   "$(jq -n \
  --arg u svc_clinical_records \
  --arg p "${CLINICAL_RECORDS_SERVICE_DB_PASSWORD}" \
  --arg m "${CLINICAL_RECORDS_SERVICE_MONGO_PASSWORD}" \
  '{data: {
    DB_USERNAME: $u,
    DB_PASSWORD: $p,
    MONGODB_URI: ("mongodb://svc_clinical_records:" + $m + "@mongo:27017/dentistdss_files?authSource=admin")
  }}')"
seed_record audit              "$(jq -n \
  --arg m "${AUDIT_SERVICE_MONGO_PASSWORD}" \
  '{data: {SPRING_DATA_MONGODB_URI: ("mongodb://svc_audit:" + $m + "@mongo:27017/dentistdss?authSource=admin")}}')"
seed_record genai              "$(jq -n \
  --arg m "${GENAI_SERVICE_MONGO_PASSWORD}" \
  '{data: {SPRING_DATA_MONGODB_URI: ("mongodb://svc_genai:" + $m + "@mongo:27017/dentistdss?authSource=admin")}}')"

cat <<EOF

Seeded 9 per-service credential records for ${ENVIRONMENT}.
Next steps (rollout runbook — see deploy/chart/README.md):
  1. Read the passwords back from Vault and apply them to the databases:
       deploy/scripts/provision-db-roles.sh   (env: PGPASSWORD + the seven
       *_SERVICE_DB_PASSWORD vars; MONGO_ADMIN_URI + the three
       *_SERVICE_MONGO_PASSWORD vars)
  2. Upgrade the chart — pods then connect with their per-service
     credentials and Flyway V3 reconciles the roles/grants as the owner.
EOF
