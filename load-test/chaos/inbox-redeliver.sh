#!/usr/bin/env bash
# #347 Inbox 멱등 측정 — consumer group offset을 earliest로 되돌려 토픽 누적 이벤트 전체를
# 원본 eventId 그대로 재전달시킨다. DURATION 동안 사이클(컨슈머 정지 → reset → 기동 → lag 소진)을
# 반복해 duplicate 트래픽을 끊김 없이 유지한다(Prometheus 증분 산출용). EC2(배포본 호스트)에서 실행.
#
# 사용법:  DURATION=360 ./inbox-redeliver.sh
#   CONTAINER  카프카 컨테이너 (기본 ticketrush-kafka, 로컬 스택은 kafka)
#   SERVICE    컨슈머 서비스 컨테이너 (기본 seat-service). reset은 그룹 비활성 상태를 요구한다.
#   GROUP      리셋할 consumer group (기본 seat-group)
#   TOPIC      재전달할 토픽 (기본 booking-created-topic)
#   DURATION   반복 유지 시간(초). 스크랩 간격 대비 안정적 증분을 위해 최소 300 이상. 기본 360.
#
# ⚠ SERVICE 재시작 동안 같은 서비스의 다른 토픽 리스너도 함께 멈춘다 — 측정 전용 스택에서만 실행.
# 출력의 UTC 타임스탬프를 PromQL 구간·report 타임라인에 쓴다.
set -euo pipefail

CONTAINER="${CONTAINER:-ticketrush-kafka}"
SERVICE="${SERVICE:-seat-service}"
GROUP="${GROUP:-seat-group}"
TOPIC="${TOPIC:-booking-created-topic}"
DURATION="${DURATION:-360}"

KCG="/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092"

# 스크립트가 중간에 끊겨도 컨슈머는 반드시 되살린다 (정상 종료 시엔 no-op)
trap 'docker start "$SERVICE" >/dev/null 2>&1 || true' EXIT

# 해당 토픽 파티션 LAG 합. lag 0은 "사이클 소진 완료"의 근거이므로 describe 실패·리밸런스 중
# LAG "-" 표시(매칭 0행)를 진짜 0과 구분해 -1로 반환한다(폴링 루프가 소진으로 오인하지 않도록).
# stderr는 숨기지 않아 실패 원인이 로그에 남는다.
lag() {
  docker exec "$CONTAINER" $KCG --describe --group "$GROUP" \
    | awk -v t="$TOPIC" '$2 == t && $6 ~ /^[0-9]+$/ { sum += $6; n++ } END { if (n == 0) print -1; else print sum }'
}

START=$(date +%s)
CYCLE=0
echo "[redeliver] begin group=${GROUP} topic=${TOPIC} duration=${DURATION}s  $(date -u '+%Y-%m-%d %H:%M:%S') UTC"

while (( $(date +%s) - START < DURATION )); do
  CYCLE=$((CYCLE + 1))

  docker stop "$SERVICE" >/dev/null
  docker exec "$CONTAINER" $KCG --group "$GROUP" --topic "$TOPIC" \
    --reset-offsets --to-earliest --execute >/dev/null
  docker start "$SERVICE" >/dev/null
  echo "[redeliver] cycle ${CYCLE} reset done  $(date -u '+%Y-%m-%d %H:%M:%S') UTC"

  # lag 0 수렴까지 폴링 후 즉시 다음 사이클 — 사이클 간 duplicate 공백을 최소화한다
  while (( $(date +%s) - START < DURATION )); do
    sleep 10
    L=$(lag)
    echo "[redeliver] cycle ${CYCLE} lag=${L}"
    (( L == 0 )) && break
  done
done

echo "[redeliver] end   cycles=${CYCLE}  $(date -u '+%Y-%m-%d %H:%M:%S') UTC"
