#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${DEPLOY_ENV_FILE:-${DEPLOY_DIR}/.env}"

if [[ ! -f "${ENV_FILE}" ]]; then
  printf 'Missing deployment environment file: %s\n' "${ENV_FILE}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

APP_BIND_ADDRESS="${APP_BIND_ADDRESS:-127.0.0.1}"
APP_PORT="${APP_PORT:-8080}"
if [[ "${APP_BIND_ADDRESS}" == "0.0.0.0" ]]; then
  APP_BIND_ADDRESS="127.0.0.1"
fi

BASE_URL="http://${APP_BIND_ADDRESS}:${APP_PORT}"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-dentistdss}"
COMPOSE=(
  docker compose
  --project-name "${PROJECT_NAME}"
  --env-file "${ENV_FILE}"
  --file "${DEPLOY_DIR}/compose.yml"
)

retry() {
  local attempts="$1"
  shift
  local delay="$1"
  shift

  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if "$@"; then
      return 0
    fi
    sleep "${delay}"
  done
  return 1
}

retry 30 5 "${COMPOSE[@]}" exec -T api-gateway \
  curl --fail --silent --show-error http://localhost:8080/actuator/health >/dev/null

if "${COMPOSE[@]}" ps --status running --services | grep -qx frontend; then
  retry 30 5 curl --fail --silent --show-error "${BASE_URL}/healthz" >/dev/null
  printf 'Gateway and frontend smoke tests passed at %s\n' "${BASE_URL}"
else
  printf 'Gateway smoke test passed; frontend is not running in this deployment.\n'
fi
