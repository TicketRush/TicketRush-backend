#!/usr/bin/env bash
# #633 회차 1 — B run 의 정상(B1)/피크(B2) 구간 분리 조회
#
# B run = 2026-09-11T12:40:24Z ~ 12:50:44Z (10m20.1s, k6 실측)
#   B1 정상 8/s   : 12:40:24 ~ 12:45:24  (300s)
#   ramp 20s      : 12:45:24 ~ 12:45:44           (분리 조회에서 제외)
#   B2 피크 12/s  : 12:45:44 ~ 12:50:44  (300s)
#
# 🔴 아래 창([5m] @ 구간종료)은 run 길이에 딱 맞춘 값이라 **앞부분이 잘린다.**
#   회차 재조회에서 실제로 밟은 함정이다(총건수 1325 vs k6 1440). 실행 전에 창을
#   [5m30s] 정도로 넓히고 @ 를 구간종료+15s 로 옮긴 뒤, 총건수를 k6 구간 iteration
#   (B1 ≈ 2400 / B2 ≈ 3600)과 대조해 창을 검산할 것.
#
# 히스토그램 버킷은 누적이므로 @ modifier 로 run 종료 시점을 고정하면
# 회차가 끝난 뒤에도 그 구간을 정확히 복원할 수 있다(런북 §17.6 정정분).
# 새 부하를 걸지 않는다 — 이미 TSDB 에 쌓인 데이터를 읽기만 한다.
set -u
P=http://localhost:9090/api/v1/query
M=ticketrush_payment_booking_lookup_seconds
S='{outcome="success",instance="payment-service:8090"}'

q() { # $1=라벨  $2=PromQL
  printf '\n--- %s ---\n' "$1"
  curl -sG "$P" --data-urlencode "query=$2" \
    | { jq -r '.data.result[] | "\(.metric.le // "-")\t\(.value[1])"' 2>/dev/null || cat; }
}

for arm in B1:1789130724 B2:1789131044; do
  name=${arm%%:*}; at=${arm##*:}
  echo "══════════════════ $name (@ $at, [5m]) ══════════════════"
  for p in 0.50 0.95 0.99; do
    q "$name p$p" "histogram_quantile($p, sum by (le) (increase(${M}_bucket${S}[5m] @ $at)))"
  done
  q "$name 총건수(count)" "sum(increase(${M}_count${S}[5m] @ $at))"
  q "$name 버킷 누적(le별)" "sum by (le) (increase(${M}_bucket${S}[5m] @ $at))"
  q "$name outcome 전체 분포" "sum by (outcome) (increase(${M}_count{instance=\"payment-service:8090\"}[5m] @ $at))"
done
