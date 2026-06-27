#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${DEPLOY_ENV_FILE:-${DEPLOY_DIR}/.env}"

if [[ ! -f "${ENV_FILE}" ]]; then
  printf 'Missing deployment environment file: %s\n' "${ENV_FILE}" >&2
  exit 1
fi

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

printf 'Gateway smoke test passed.\n'
