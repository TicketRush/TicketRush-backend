#!/usr/bin/env bash
# #496 장애 주입 — 검표 경로가 의존하는 booking-service 를 일시적으로 못 쓰게 만든다.
# EC2(배포본 호스트)에서 실행. 측정 전용이며 원복은 trap 이 보장한다.
#
# 사용법:  MODE=pause OUTAGE_SEC=120 ./booking-outage.sh
#   MODE        pause(기본) | stop
#   OUTAGE_SEC  주입 유지 시간(초). 기본 120.
#   CONTAINER   대상 컨테이너 (기본 booking-service)
#
# 두 모드는 서로 다른 실패 경로를 친다. 둘 다 돌려야 격리가 완결됐다고 말할 수 있다.
#   pause  프로세스만 멈추고 소켓은 살아 있다 -> 연결은 되는데 응답이 없다 = read-timeout 경로.
#          이 이슈가 겨냥한 "booking 이 죽지 않고 느려지는" 상황의 재현이다. tc netem 을 쓰지 않는
#          이유는 컨테이너에 NET_ADMIN 이 필요해 compose 를 건드려야 하는데, docker pause 가
#          같은 것을 무설정으로 주기 때문이다.
#   stop   컨테이너가 사라져 소켓 자체가 거부된다 = connect 실패 경로(즉시 실패).
#
# 주의: booking-service 를 멈추면 예매 생성 경로도 함께 멈춘다. 검표 전용 측정 창에서만 쓴다.
#       docker pause 상태의 컨테이너는 healthcheck 도 멈추므로, compose 의 depends_on 재기동이
#       걸리지 않게 주입 중에는 up/restart 를 실행하지 않는다.
#
# 출력의 UTC 타임스탬프를 Prometheus 조회 구간(런북 §12.6) 산정에 쓴다.
set -euo pipefail

CONTAINER="${CONTAINER:-booking-service}"
MODE="${MODE:-pause}"
OUTAGE_SEC="${OUTAGE_SEC:-120}"

case "$MODE" in
  pause) INJECT=(docker pause "$CONTAINER"); RECOVER=(docker unpause "$CONTAINER") ;;
  stop)  INJECT=(docker stop "$CONTAINER");  RECOVER=(docker start "$CONTAINER") ;;
  *) echo "MODE 는 pause 또는 stop 이어야 한다 (받은 값: $MODE)" >&2; exit 1 ;;
esac

# 스크립트가 중간에 끊겨도 booking 은 반드시 되살린다 (정상 종료 시엔 no-op).
# unpause/start 는 이미 정상인 컨테이너에 대고 돌면 실패하므로 실패를 삼킨다.
trap '"${RECOVER[@]}" >/dev/null 2>&1 || true' EXIT

echo "[outage] ${MODE}   ${CONTAINER}  $(date -u '+%Y-%m-%d %H:%M:%S') UTC"
"${INJECT[@]}" >/dev/null
sleep "$OUTAGE_SEC"
"${RECOVER[@]}" >/dev/null
echo "[outage] recover ${CONTAINER}  $(date -u '+%Y-%m-%d %H:%M:%S') UTC (outage ${OUTAGE_SEC}s, mode ${MODE})"
