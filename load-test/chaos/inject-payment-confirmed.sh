#!/usr/bin/env bash
# #504 결제확정 이벤트 직접 주입 — payment-confirmed-topic 에 이벤트를 밀어 넣고 backlog 가
# 빠지는 데 걸리는 시간을 잰다. EC2(배포본 호스트)에서 실행.
#
# 왜 결제 API 를 안 타나: StubPaymentApprovalClient 가 @Profile("!prod") 이고 측정 대상은
# prod 단독 배포본이다(ADR 0004). 실 Toss 호출은 paymentKey 를 PG 가 발급하므로 만들 수 없다.
# 그래서 파이프라인 입구에 직접 넣는다 — #402 가 seed_entry.sql 로 코호트를 SQL 로 심은 것과
# 같은 사상이다.
#
# 사용법:  BOOKING_ID_MIN=.. SEAT_ID_MIN=.. USER_ID=.. COUNT=.. ./inject-payment-confirmed.sh
#   BOOKING_ID_MIN  코호트 첫 booking_id   (seed_payment_pipeline.sql 검증 SELECT 의 값)
#   SEAT_ID_MIN     코호트 첫 seat_id      (   ''   )
#   USER_ID         코호트 소유자          (   ''   )
#   COUNT           주입 건수 (기본 1000)
#   OFFSET          코호트 내 시작 인덱스 (기본 0). baseline 과 스파이크가 같은 건을 두 번
#                   쓰지 않게 구간을 나눈다 — 두 번째 주입은 booking 이 이미 CONFIRMED 라
#                   confirm() 이 no-op 이 되고 티켓도 already_issued 가 되어 유입이 깎인다.
#   RATE            초당 건수 (기본 0 = 무페이싱, 최대 속도)
#   AMOUNT          결제 금액 (기본 10000)
#   DRAIN_WAIT      주입 후 lag 0 까지 폴링할지 (기본 1)
#   DRAIN_TIMEOUT   폴링 상한(초, 기본 1800)
#   CONTAINER       Kafka 컨테이너 (기본 ticketrush-kafka)
#   BOOTSTRAP       브로커 (기본 localhost:29092 — prod 리스너. 로컬 compose 의 9092 가 아니다)
#
# 출력의 UTC 타임스탬프가 PromQL 구간 산정의 SSOT 다. 리포트에는 명목 RATE 가 아니라 여기 찍히는
# **실측 주입률**(총건수/경과초)을 쓴다.
#
# ⚠ 스파이크는 페이싱하지 않는다(RATE=0). 이 회차가 답할 질문이 "적체가 얼마 만에 빠지는가"인데,
#   주입 종료 후 유입이 정확히 0이어야 lag 하강 기울기가 그대로 드레인율이 된다. 페이싱하면
#   회복 앞부분에 유입이 섞여 기울기가 혼탁해진다.
#
# ⚠ 워킹트리가 CRLF 면 ssh 로 넘길 때 첫 줄부터 죽는다(런북 §10.2). 반드시:
#     tr -d '\r' < load-test/chaos/inject-payment-confirmed.sh | ssh <host> 'cat > /tmp/inject.sh'
#     ssh <host> 'BOOKING_ID_MIN=.. bash /tmp/inject.sh'
set -euo pipefail

CONTAINER="${CONTAINER:-ticketrush-kafka}"
BOOTSTRAP="${BOOTSTRAP:-localhost:29092}"
TOPIC="${TOPIC:-payment-confirmed-topic}"
CONSUMER_GROUPS="${CONSUMER_GROUPS:-booking-group ticket-group}"
COUNT="${COUNT:-1000}"
OFFSET="${OFFSET:-0}"
RATE="${RATE:-0}"
AMOUNT="${AMOUNT:-10000}"
DRAIN_WAIT="${DRAIN_WAIT:-1}"
DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-1800}"

for v in BOOKING_ID_MIN SEAT_ID_MIN USER_ID; do
  if [ -z "${!v:-}" ]; then
    echo "$v 가 필요하다. seed_payment_pipeline.sql 의 검증 SELECT 가 내는 값을 넘긴다." >&2
    exit 1
  fi
done

KCG="/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server $BOOTSTRAP"
KCP="/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $BOOTSTRAP"

# 봉투 value 는 spring-kafka 의 JacksonJsonDeserializer 가 읽는다. USE_TYPE_INFO_HEADERS=true 이고
# spring.json.value.default.type 이 설정돼 있지 않아(KafkaConfig.java:119), 이 헤더가 없으면
# "No type information in headers and no default type provided" 로 죽는다. 그리고 그 예외는
# addNotRetryableExceptions 에 걸려 **재시도 없이 즉시 DLT** 로 간다 — 리스너는 실행조차 안 된다.
HDR='__TypeId__:com.ticketrush.global.event.DomainEventEnvelope'

# preflight — 조용히 빈 결과를 남기면 측정을 통째로 날린다(outbox-sampler.sh 와 같은 규약).
if ! docker exec "$CONTAINER" $KCG --list >/dev/null 2>&1; then
  echo "preflight 실패: $CONTAINER 에서 브로커($BOOTSTRAP)에 닿지 못했다." >&2
  echo "  prod 리스너는 29092 다. 로컬 compose(9092)와 다르다." >&2
  exit 1
fi
# ⚠ 여기서 `... | grep -q` 를 쓰면 안 된다. grep -q 는 첫 매치에서 빠져나가며 docker exec 에
#   SIGPIPE(141)를 보내고, set -o pipefail 이 그걸 파이프라인 실패로 읽어 preflight 가 항상
#   거짓 실패한다. 출력을 먼저 받아 두고 셸 패턴 매칭으로 본다(파이프 없음).
#
# 그룹당 컨슈머 수 = 소비 병렬도다. 완료조건 5가 요구하는 값이라 회차 로그에 남긴다.
echo "[inject] topic   $TOPIC  partitions=$(docker exec "$CONTAINER" \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --describe --topic "$TOPIC" \
  2>/dev/null | awk -F'PartitionCount: ' 'NR==1 { split($2, a, "\t"); print a[1] }')"
for g in $CONSUMER_GROUPS; do
  desc=$(docker exec "$CONTAINER" $KCG --describe --group "$g" 2>/dev/null || true)
  case "$desc" in
    *"$TOPIC"*) ;;
    *)
      echo "preflight 실패: $g 가 $TOPIC 을 구독하고 있지 않다(컨슈머 미기동?)." >&2
      echo "  auto.offset.reset=latest 라 컨슈머가 붙기 전에 주입하면 그 분량을 통째로 건너뛴다." >&2
      exit 1
      ;;
  esac
  echo "[inject] group   $g  consumers=$(printf '%s\n' "$desc" \
    | awk -v t="$TOPIC" '$2 == t { print $7 }' | sort -u | wc -l)"
done

# 컨슈머 그룹의 LAG 합. inbox-redeliver.sh 의 관용구를 그대로 쓴다 — 리밸런스 중에는 LAG 이 '-'
# 로 나오는데 그걸 진짜 0 으로 읽으면 "이미 다 빠졌다"고 오판하므로 매칭 0행은 -1 로 낸다.
lag() {
  docker exec "$CONTAINER" $KCG --describe --group "$1" 2>/dev/null \
    | awk -v t="$TOPIC" '$2 == t && $6 ~ /^[0-9]+$/ { sum += $6; n++ } END { if (n == 0) print -1; else print sum }'
}

RUN_ID="${RUN_ID:-$(date -u +%s)$$}"
CREATED_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
PAID_AT=$(date -u '+%Y-%m-%d %H:%M:%S')

echo "[inject] begin   count=$COUNT offset=$OFFSET rate=${RATE:-0} run=$RUN_ID  $(date -u '+%Y-%m-%d %H:%M:%S') UTC"
START=$(date +%s)

# 한 줄 형식은 parse.headers=true + parse.key=true 일 때 "headers<TAB>key<TAB>value" 다.
# 구분자는 첫 출현만 보고 value 는 남은 줄 전체이므로, JSON 안의 콤마·콜론·중괄호·이스케이프된
# 따옴표는 전부 안전하다. 금지 문자는 TAB 과 개행뿐이다.
#
# payload 는 봉투 안에 **문자열로 escape 되어** 들어간다. 봉투는 camelCase(spring-kafka 전용
# 매퍼)지만 payload 는 앱 ObjectMapper 라 snake_case + 'yyyy-MM-dd HH:mm:ss' 다(JacksonConfig).
# 두 규칙이 한 메시지 안에서 다르다 — 실제 outbox.payload 로 확인한 형태다.
gen() {
  awk -v n="$COUNT" -v off="$OFFSET" -v bkmin="$BOOKING_ID_MIN" -v stmin="$SEAT_ID_MIN" \
      -v uid="$USER_ID" -v amt="$AMOUNT" -v run="$RUN_ID" \
      -v created="$CREATED_AT" -v paid="$PAID_AT" -v hdr="$HDR" -v topic="$TOPIC" '
    BEGIN {
      q = "\\\""                      # payload 안에서 쓰일 이스케이프된 따옴표
      for (i = 0; i < n; i++) {
        idx = off + i
        bid = bkmin + idx
        sid = stmin + idx
        # eventId 는 매 건 유일해야 한다. InboxService.runIfFirst 가 (consumer_group, event_id)
        # 로 멱등을 판정하므로 중복 ID 는 조용히 스킵되어 유입이 실제보다 적어진다.
        # inbox.event_id 는 varchar(36) — 아래 형식은 최대 32자다.
        payload = "{" q "payment_id" q ":" bid "," q "booking_id" q ":" bid "," \
                  q "seat_id" q ":" sid "," q "user_id" q ":" uid "," \
                  q "amount" q ":" amt "," q "paid_at" q ":" q paid q "}"
        printf "%s\t%d\t{\"eventId\":\"p504-%s-%09d\",\"eventType\":\"PaymentConfirmed\",\"createdAt\":\"%s\",\"topic\":\"%s\",\"payload\":\"%s\",\"traceId\":null}\n", \
               hdr, bid, run, idx, created, topic, payload
      }
    }'
}

# RATE>0 이면 초당 RATE 줄씩 흘린다. 매 초 fork(sleep) 오버헤드와 줄 생성 시간이 주기에 섞여
# 드리프트가 **느린 쪽으로만** 누적되므로, 명목 RATE 를 리포트 표에 쓰지 않는다 — 아래 end 로그의
# 실측값을 쓴다. 스파이크는 RATE=0 으로 두는 것이 정확하다(위 헤더 주석 참고).
pace() {
  if [ "$RATE" -le 0 ]; then cat; else
    awk -v rate="$RATE" '{ print; if (++c % rate == 0) { fflush(); system("sleep 1") } }'
  fi
}

gen | pace | docker exec -i "$CONTAINER" $KCP --topic "$TOPIC" \
  --property parse.headers=true --property parse.key=true

END=$(date +%s)
ELAPSED=$(( END - START )); [ "$ELAPSED" -gt 0 ] || ELAPSED=1
echo "[inject] end     count=$COUNT elapsed=${ELAPSED}s actual_rate=$(( COUNT / ELAPSED ))/s  $(date -u '+%Y-%m-%d %H:%M:%S') UTC"

[ "$DRAIN_WAIT" = "1" ] || exit 0

# 드레인 대기 — 여기서 나오는 "drained" 시각이 회복시간의 종점이다. 브로커의 LEO−committed 라
# 클라이언트측 records-lag(마지막 fetch 응답 시점 값)보다 권위 있다. 그래프는 Prometheus 15초
# 스크랩으로 그리고, 숫자는 이 루프에서 뽑는다.
echo "[drain]  begin   $(date -u '+%Y-%m-%d %H:%M:%S') UTC"
DRAIN_START=$(date +%s)
while true; do
  now=$(date +%s)
  line=""; total=0; unknown=0
  for g in $CONSUMER_GROUPS; do
    l=$(lag "$g")
    line="$line $g=$l"
    if [ "$l" -lt 0 ]; then unknown=1; else total=$(( total + l )); fi
  done
  echo "[drain]  $(date -u '+%Y-%m-%dT%H:%M:%SZ')$line total=$total"

  if [ "$unknown" -eq 0 ] && [ "$total" -eq 0 ]; then
    echo "[drain]  drained elapsed_from_inject_end=$(( now - END ))s  $(date -u '+%Y-%m-%d %H:%M:%S') UTC"
    break
  fi
  if [ $(( now - DRAIN_START )) -ge "$DRAIN_TIMEOUT" ]; then
    echo "[drain]  timeout after ${DRAIN_TIMEOUT}s — 아직 total=$total. 회차를 폐기하거나 상한을 올린다." >&2
    exit 1
  fi
  sleep 5
done
