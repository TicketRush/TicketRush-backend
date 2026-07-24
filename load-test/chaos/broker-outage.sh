#!/usr/bin/env bash
# #346 장애 주입 — Kafka 브로커를 강제 정지했다가 되살린다. EC2(배포본 호스트)에서 실행.
#
# 사용법:  OUTAGE_SEC=180 ./broker-outage.sh
#   CONTAINER  대상 컨테이너 (기본 ticketrush-kafka, 로컬 스택은 kafka)
#   OUTAGE_SEC 정지 유지 시간(초). 프로듀서 기본 delivery.timeout.ms=120s를 넘겨야
#              버퍼된 send가 만료되어 AFTER_COMMIT 유실이 실제로 재현된다. 기본 180.
#
# 출력의 UTC 타임스탬프를 verify-loss.sql의 @from/@to 구간 산정에 쓴다.
set -euo pipefail

CONTAINER="${CONTAINER:-ticketrush-kafka}"
OUTAGE_SEC="${OUTAGE_SEC:-180}"

# 스크립트가 중간에 끊겨도 브로커는 반드시 되살린다 (정상 종료 시엔 no-op)
trap 'docker start "$CONTAINER" >/dev/null 2>&1 || true' EXIT

echo "[outage] stop  ${CONTAINER}  $(date -u '+%Y-%m-%d %H:%M:%S') UTC"
docker stop "$CONTAINER" >/dev/null
sleep "$OUTAGE_SEC"
docker start "$CONTAINER" >/dev/null
echo "[outage] start ${CONTAINER}  $(date -u '+%Y-%m-%d %H:%M:%S') UTC (outage ${OUTAGE_SEC}s)"
