#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${DEPLOY_DIR}/compose.yml"
ENV_FILE="${DEPLOY_ENV_FILE:-${DEPLOY_DIR}/.env}"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-dentistdss}"

if [[ ! -f "${ENV_FILE}" ]]; then
  printf 'Missing deployment environment file: %s\n' "${ENV_FILE}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

if [[ ",${COMPOSE_PROFILES:-}," == *",ai,"* ]]; then
  : "${VERTEX_AI_PROJECT_ID:?VERTEX_AI_PROJECT_ID is required for the ai profile}"
  : "${GEMINI_MODEL:?GEMINI_MODEL is required for the ai profile}"
  : "${GCP_CREDENTIALS_PATH:?GCP_CREDENTIALS_PATH is required for the ai profile}"
  if [[ ! -r "${GCP_CREDENTIALS_PATH}" ]]; then
    printf 'GCP credentials are not readable: %s\n' "${GCP_CREDENTIALS_PATH}" >&2
    exit 1
  fi
fi

export DOCKER_BUILDKIT=1

COMPOSE=(
  docker compose
  --project-name "${PROJECT_NAME}"
  --env-file "${ENV_FILE}"
  --file "${COMPOSE_FILE}"
)

"${COMPOSE[@]}" config --quiet

SERVICES=()
if [[ -n "${DEPLOY_SERVICES:-}" ]]; then
  # shellcheck disable=SC2206
  SERVICES=(${DEPLOY_SERVICES})
fi

"${COMPOSE[@]}" pull "${SERVICES[@]}"

"${COMPOSE[@]}" up \
  --detach \
  --remove-orphans \
  --wait \
  --wait-timeout 600 \
  "${SERVICES[@]}"

"${SCRIPT_DIR}/smoke-test.sh"

docker image prune --force --filter 'until=168h'
