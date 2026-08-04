#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(
  cd "$(dirname "${BASH_SOURCE[0]}")"
  pwd
)"

DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${ENV_FILE:-$DEPLOY_DIR/.env}"
NEW_IMAGE_TAG="${1:-${IMAGE_TAG:-}}"

if [[ -z "${NEW_IMAGE_TAG}" ]]; then
  echo "ERROR: 새 IMAGE_TAG를 인자로 전달해야 합니다." >&2
  exit 2
fi

if [[ ! "${NEW_IMAGE_TAG}" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]; then
  echo "ERROR: 유효하지 않은 Docker 이미지 태그입니다." >&2
  exit 2
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "ERROR: 환경파일을 찾을 수 없습니다: ${ENV_FILE}" >&2
  exit 2
fi

TEMP_FILE="$(mktemp "${ENV_FILE}.tmp.XXXXXX")"

cleanup() {
  rm -f "${TEMP_FILE}"
}

trap cleanup EXIT

awk -v image_tag="${NEW_IMAGE_TAG}" '
  BEGIN {
    updated = 0
  }

  /^[[:space:]]*IMAGE_TAG[[:space:]]*=/ {
    if (!updated) {
      print "IMAGE_TAG=" image_tag
      updated = 1
    }

    next
  }

  {
    print
  }

  END {
    if (!updated) {
      print "IMAGE_TAG=" image_tag
    }
  }
' "${ENV_FILE}" > "${TEMP_FILE}"

chmod 600 "${TEMP_FILE}"
mv "${TEMP_FILE}" "${ENV_FILE}"

trap - EXIT

echo "IMAGE_TAG를 ${ENV_FILE}에 반영했습니다."
