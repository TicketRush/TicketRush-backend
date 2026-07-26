#!/usr/bin/env bash
# #489 측정 — outbox 릴레이 적체·in-flight·발행 카운터 샘플러. EC2(배포본 호스트)에서 실행.
#
# Prometheus 가 같은 지표를 15초로 긁지만 그것만으로는 부족한 이유가 둘 있다.
#
#  1. 배포본에 batch-size 가 실제로 물렸는지 확인할 수단이 게이지뿐이다. actuator 는 health·info·
#     prometheus 3개만 노출해 env 를 볼 수 없다(런북 §11.4-3 과 같은 제약). in_flight 가 100 을
#     넘는 표본이 한 번이라도 잡히면 batchSize > 100 이 확정되는데, 15초 스크랩은 그 피크를 놓칠 수 있다.
#  2. 리포트 표에 넣을 발행률·in-flight 피크를 Prometheus 보존·스크랩 결손과 무관한 원본에서 계산한다.
#
# 사용법:  DURATION=900 INTERVAL=5 ./outbox-sampler.sh > outbox-b1.csv
#   SERVICES   대상 컨테이너 (기본 "seat-service booking-service" — 만료 전파의 두 홉)
#   INTERVAL   샘플 간격(초, 기본 5). 릴레이 주기가 5초라 그보다 촘촘히 떠도 새 값이 없다.
#   DURATION   총 관측 시간(초, 기본 900 = 15분). 만료 10,000건은 이전 실측에서 13분 걸렸다.
#   PORT       actuator 포트(기본 8090)
#
# ⚠ backlog 는 relayBatch() 안에서만 갱신된다. 릴레이 스레드가 죽으면 마지막 값이 그대로 계속 나오므로,
#   값이 낮다고 안전한 게 아니라 관측이 멎은 것일 수 있다 — relay_success 가 함께 멈췄는지 본다(런북 §10.3).
#
# 컨테이너마다 docker exec 을 새로 띄우는 오버헤드(수백 ms)가 간격에 섞이는 것은 감수한다. 서비스 2개 ×
# 5초 간격이라 trx-sampler.sh 처럼 루프를 안으로 넣을 이득이 없고, 여기선 앱 컨테이너를 오가야 한다.
set -euo pipefail

SERVICES="${SERVICES:-seat-service booking-service}"
INTERVAL="${INTERVAL:-5}"
DURATION="${DURATION:-900}"
PORT="${PORT:-8090}"

echo "ts_utc,service,backlog,in_flight,relay_success,relay_fail"

# actuator 텍스트에서 지표 하나를 뽑는다. 없으면 빈 문자열(= CSV 공란)로 둔다 — 0 으로 채우면
# "지표가 없다"와 "값이 0이다"가 구분되지 않는다.
metric() {
  echo "$1" | awk -v pat="$2" '$0 ~ pat && $0 !~ /^#/ { print $2; exit }'
}

end=$(( $(date +%s) + DURATION ))
while [ "$(date +%s)" -lt "$end" ]; do
  ts=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

  for svc in $SERVICES; do
    raw=$(docker exec "$svc" curl -s "localhost:${PORT}/actuator/prometheus" 2>/dev/null || true)

    echo "${ts},${svc},$(metric "$raw" '^ticketrush_outbox_backlog'),$(metric "$raw" '^ticketrush_outbox_in_flight'),$(metric "$raw" '^ticketrush_outbox_relay_total.*result="success"'),$(metric "$raw" '^ticketrush_outbox_relay_total.*result="fail"')"
  done

  sleep "$INTERVAL"
done
