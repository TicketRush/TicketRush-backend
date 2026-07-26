#!/usr/bin/env bash
# #345 측정 — MySQL 트랜잭션 지속시간·락 점유 샘플러. EC2(배포본 호스트)에서 실행.
#
# 앱에는 트랜잭션 지속시간 지표가 없다(Micrometer 계측 없음). information_schema.innodb_trx 를
# 주기적으로 떠서 "그 순간 살아 있는 트랜잭션 중 최장"을 기록한다.
#
# 사용법:  DURATION=660 ./trx-sampler.sh > trx-samples-a1.csv
#   CONTAINER  MySQL 컨테이너 (기본 ticketrush-mysql)
#   INTERVAL   샘플 간격(초, 기본 1)
#   DURATION   총 관측 시간(초, 기본 660 = 11분)
#
# ⚠ 이 값은 최장 지속시간의 **하한**이다. 청크 트랜잭션(25건)은 수십 ms급이라 샘플 사이에 끝나면
#   기록되지 않는다. 단일 트랜잭션 arm(2,000건)은 초 단위라 안정적으로 잡힌다. 리포트에 이 한계를 명시한다.
#
# 루프 전체를 컨테이너 안에서 돌린다 — 샘플마다 docker exec 을 새로 띄우면 그 오버헤드(수백 ms)가
# 간격에 섞인다. 타임스탬프도 컨테이너 안에서 찍으므로(프로드 컨테이너는 UTC) 실제 cadence 가 CSV 에 남는다.
# 비밀번호는 컨테이너 안에서 $MYSQL_ROOT_PASSWORD 를 펼쳐 쓰므로 셸 히스토리에 평문이 남지 않는다.
set -euo pipefail

CONTAINER="${CONTAINER:-ticketrush-mysql}"
INTERVAL="${INTERVAL:-1}"
DURATION="${DURATION:-660}"

echo "ts_utc,max_secs_running,max_rows_locked,max_lock_structs,lock_wait_trx,active_trx,data_lock_waits"

docker exec -e INTERVAL="$INTERVAL" -e DURATION="$DURATION" -i "$CONTAINER" sh -s <<'EOS'
set -eu
# trx_started 는 서버 타임존 기준이고 NOW(6) 도 같은 기준이라 차이는 타임존과 무관하다.
# 빈 집합(활성 트랜잭션 0건)에서 MAX/SUM 은 NULL 이므로 전부 COALESCE 한다.
SQL='SELECT
  COALESCE(MAX(TIMESTAMPDIFF(MICROSECOND, trx_started, NOW(6))) / 1000000, 0),
  COALESCE(MAX(trx_rows_locked), 0),
  COALESCE(MAX(trx_lock_structs), 0),
  COALESCE(SUM(trx_state = "LOCK WAIT"), 0),
  COUNT(*),
  (SELECT COUNT(*) FROM performance_schema.data_lock_waits)
FROM information_schema.innodb_trx'

end=$(( $(date +%s) + DURATION ))
while [ "$(date +%s)" -lt "$end" ]; do
  ts=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  row=$(mysql -u root -p"$MYSQL_ROOT_PASSWORD" -N -B -e "$SQL" | tr '\t' ',')
  echo "${ts},${row}"
  sleep "$INTERVAL"
done
EOS
