#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(
  cd "$(dirname "${BASH_SOURCE[0]}")"
  pwd
)"

DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${ENV_FILE:-$DEPLOY_DIR/.env}"
EXPECTED_IMAGE_TAG="${EXPECTED_IMAGE_TAG:-}"

APP_CONTAINERS=(
  auth-service
  booking-service
  gateway-service
  payment-service
  performance-service
  seat-service
  ticket-service
  user-service
)

trim_value() {
  local value="$1"

  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  value="${value%\"}"
  value="${value#\"}"
  value="${value%\'}"
  value="${value#\'}"

  printf '%s' "$value"
}

if [[ -n "${EXPECTED_IMAGE_TAG}" ]]; then
  expected_tag="$(trim_value "${EXPECTED_IMAGE_TAG}")"
  expected_source="EXPECTED_IMAGE_TAG"
else
  if [[ ! -f "${ENV_FILE}" ]]; then
    echo "ERROR: 환경파일을 찾을 수 없습니다: ${ENV_FILE}" >&2
    exit 2
  fi

  expected_tag="$(
    sed -nE \
      's/^[[:space:]]*IMAGE_TAG[[:space:]]*=[[:space:]]*(.*)$/\1/p' \
      "${ENV_FILE}" \
      | tail -n 1 \
      | tr -d '\r'
  )"

  expected_tag="$(trim_value "${expected_tag}")"
  expected_source="${ENV_FILE}"
fi

if [[ -z "${expected_tag}" ]]; then
  echo "ERROR: 비교할 IMAGE_TAG가 없습니다." >&2
  exit 2
fi

echo "기준 IMAGE_TAG (${expected_source}): ${expected_tag}"
echo

printf '%-24s %-42s %-10s\n' \
  "CONTAINER" \
  "RUNNING_TAG" \
  "RESULT"

drift_found=0
runtime_error=0

for container in "${APP_CONTAINERS[@]}"
do
  if ! docker inspect "${container}" >/dev/null 2>&1; then
    printf '%-24s %-42s %-10s\n' \
      "${container}" \
      "-" \
      "NOT_FOUND"

    runtime_error=1
    continue
  fi

  is_running="$(
    docker inspect \
      --format '{{.State.Running}}' \
      "${container}"
  )"

  if [[ "${is_running}" != "true" ]]; then
    printf '%-24s %-42s %-10s\n' \
      "${container}" \
      "-" \
      "STOPPED"

    runtime_error=1
    continue
  fi

  image="$(
    docker inspect \
      --format '{{.Config.Image}}' \
      "${container}"
  )"

  case "${image}" in
    *@sha256:*)
      running_tag="${image##*@}"
      ;;
    *:*)
      running_tag="${image##*:}"
      ;;
    *)
      running_tag="<태그 없음>"
      ;;
  esac

  if [[ "${running_tag}" == "${expected_tag}" ]]; then
    result="OK"
  else
    result="DRIFT"
    drift_found=1
  fi

  printf '%-24s %-42s %-10s\n' \
    "${container}" \
    "${running_tag}" \
    "${result}"
done

echo

if [[ "${runtime_error}" -ne 0 ]]; then
  echo "ERROR: 일부 애플리케이션 컨테이너를 확인할 수 없습니다." >&2
  exit 2
fi

if [[ "${drift_found}" -ne 0 ]]; then
  echo "FAIL: 기준 IMAGE_TAG와 실행 중 애플리케이션 이미지 태그가 다릅니다." >&2
  exit 1
fi

echo "OK: 기준 IMAGE_TAG와 실행 중 애플리케이션 이미지 태그가 일치합니다."
