#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${DEPLOY_ENV_FILE:-${DEPLOY_DIR}/.env}"
BACKUP_ROOT="${BACKUP_ROOT:-${DEPLOY_DIR}/backups}"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-dentistdss}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DESTINATION="${BACKUP_ROOT}/${TIMESTAMP}"

if [[ ! -f "${ENV_FILE}" ]]; then
  printf 'Missing deployment environment file: %s\n' "${ENV_FILE}" >&2
  exit 1
fi

mkdir -p "${DESTINATION}"

docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" \
  --file "${DEPLOY_DIR}/compose.yml" exec -T postgres \
  pg_dump --username dentistdss --dbname dentistdss --format=custom \
  >"${DESTINATION}/postgres.dump"

docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" \
  --file "${DEPLOY_DIR}/compose.yml" exec -T mongo \
  sh -c 'mongodump --quiet --archive --gzip --username dentistdss --password "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin' \
  >"${DESTINATION}/mongo.archive.gz"

shasum -a 256 "${DESTINATION}/postgres.dump" "${DESTINATION}/mongo.archive.gz" \
  >"${DESTINATION}/SHA256SUMS"

find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d -mtime +"${BACKUP_RETENTION_DAYS:-14}" -exec rm -rf -- {} +

printf 'Backup written to %s\n' "${DESTINATION}"
