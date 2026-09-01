package com.ticketrush.global.constants;

/**
 * 비즈니스 메트릭(Micrometer) 이름·태그 상수(#335). 메트릭 이름은 dot 표기이며 Prometheus에서는 '_'로 변환된다(예:
 * ticketrush.booking.created → ticketrush_booking_created_total). 태그 값은 카디널리티 폭발을 막기 위해 유한 집합(아래
 * 상수)만 사용하고 ID류는 태그로 쓰지 않는다.
 */
public class MetricNames {

  private MetricNames() {}

  // ===== Metric names =====
  // booking
  public static final String BOOKING_CREATED = "ticketrush.booking.created";

  // seat
  public static final String SEAT_HOLD = "ticketrush.seat.hold";
  public static final String SEAT_LOCK_CONTENTION = "ticketrush.seat.lock.contention";
  public static final String SEAT_HELD = "ticketrush.seat.held"; // Gauge (전역 미만료 HOLD 총수)
  // 만료됐으나 아직 해제되지 않은 HOLD 총수 = 만료 fallback의 처리 적체(#345). SEAT_HELD가 미만료만 세므로
  // 대량 만료의 해소 진행은 그 게이지에 나타나지 않는다. outbox.backlog의 좌석 버전.
  public static final String SEAT_HOLD_EXPIRED_BACKLOG = "ticketrush.seat.hold.expired_backlog";
  // SSE 전송 스레드풀이 큐 포화로 거부한 이벤트 수(#403). 거부는 지연이 아니라 유실이고 구독자는
  // 이벤트가 안 왔다는 사실조차 모르므로, 이 카운터가 유일한 관측 축이다. #403 실측에서 회차 하나에
  // 2,009건(시도의 10.3%)이 사라졌는데 그걸 로그 파싱으로만 셀 수 있었다.
  public static final String SEAT_SSE_EVENT_REJECTED = "ticketrush.seat.sse.event.rejected";
  // SSE 전송 큐에 들어간 이벤트를 발행 경로별로 센다(#520). REJECTED가 "얼마나 잃었나"를 말한다면 이쪽은
  // "그 큐를 누가 채웠나"를 말한다. #403은 큐 깊이가 평균 50·최대 307까지 튀는 것을 봤지만 도착 불균일의
  // 출처를 가르지 못했다(리포트 §10) — executor_queued_tasks와 겹쳐 읽으라고 있는 축이다.
  // 태그 값은 SeatEventSource 열거형이라 유한 집합이 컴파일 시점에 보장된다.
  public static final String SEAT_SSE_EVENT_PUBLISHED = "ticketrush.seat.sse.event.published";
  // SSE 전송 큐 포화로 호출 스레드가 팬아웃을 대신 실행한 횟수(#532). 거부 정책을 CallerRunsPolicy로
  // 바꾸면서 REJECTED(유실)는 0이 되는 대신 비용이 "호출 스레드 지연"으로 옮겨 갔다 — 이 카운터가
  // 그 지불한 비용을 센다. 값이 튀면 스케줄러 tick·Redis 리스너가 그만큼 밀리고 있다는 뜻이다.
  public static final String SEAT_SSE_EVENT_CALLER_RUNS = "ticketrush.seat.sse.event.caller_runs";
  // 좌석맵 조회 캐시 히트/미스(#469). "히트 시 DB를 거치지 않는다"는 완료 조건의 운영 관측 축이고,
  // 티켓팅 러시(선점마다 evict)에서 히트율이 실제로 얼마나 남는지 측정 회차가 이 카운터로 읽는다.
  public static final String SEAT_MAP_CACHE = "ticketrush.seat.seatmap.cache";
  // 환불 좌석 반환을 실행하지 않고 건너뛴 횟수(#608). 스킵은 예외가 아니라 정상 return이라 리스너의
  // [CRITICAL] catch에 걸리지 않고 조용히 ack되고, SEAT_SSE_EVENT_PUBLISHED는 반환에 성공했을 때만
  // 오른다 — 스킵은 기존 어느 시계열에도 나타나지 않으므로 이 카운터가 유일한 관측 축이다.
  // reason 태그는 코드 분기와 1:1인 유한 집합 4종이다 — seat_not_found(좌석 부재), not_sold(SOLD가
  // 아님 = 멱등 정상 스킵), booking_number_mismatch(좌석이 이미 다른 예매의 것 = ABA 방어 발화),
  // booking_number_missing(이벤트에 예매번호가 없음).
  // 마지막 값만 성격이 다르다. payment가 값을 채우도록 고친 뒤에는(#608) 도달할 수 없어야 하므로,
  // 0이 아니면 발행 측이 계약을 어겼다는 신호다. 그리고 그 건의 좌석은 SOLD로 남는데 관리자 강제
  // 해제가 SOLD를 거부하므로(SEAT_SOLD_NOT_RELEASABLE) 수동 DML 외에 푸는 수단이 없다 — 정상 스킵과
  // 한 덩어리로 세면 그 사고가 멱등 스킵의 노이즈에 묻힌다.
  public static final String SEAT_REFUND_RELEASE_SKIPPED = "ticketrush.seat.refund_release.skipped";

  // payment
  public static final String PAYMENT_CONFIRM = "ticketrush.payment.confirm";
  public static final String PAYMENT_PG_APPROVE = "ticketrush.payment.pg.approve"; // Timer
  public static final String PAYMENT_REFUND = "ticketrush.payment.refund";
  public static final String PAYMENT_PG_CANCEL = "ticketrush.payment.pg.cancel"; // Timer
  // PG 취소 4xx 중 재시도 대상(TossCancelRetryableCode)을 만난 횟수(#573). reason 태그는 재시도를 유발한
  // Toss 원본 code(화이트리스트 3종이라 유한 집합), outcome은 네 갈래다 — succeeded(재시도 성공),
  // exhausted(재시도에서도 같은 부류 거절 → 환불 실패 확정), failed(재시도가 다른 실패로 끝남: 통신 오류·
  // 다른 결정적 코드·대기 중 인터럽트), disabled(킬 스위치로 재시도 건너뜀).
  // exhausted와 failed를 반드시 갈라야 한다. 이 카운터의 목적이 "재시도 유효성이 문서로 확인되지 않은
  // PROVIDER_ERROR가 실제로 효과가 있는가"의 사후 판정인데, 한 값에 접으면 판정이 오염된다 — 특히 409 뒤
  // 재시도에서 ALREADY_CANCELED_PAYMENT가 오는 건 선행 요청이 성공했다는 뜻이라 의미가 정반대다.
  // PAYMENT_PG_CANCEL Timer가 재시도 구간을 포함해 늘어나는 것도 이 카운터로 설명된다.
  public static final String PAYMENT_PG_CANCEL_RETRY = "ticketrush.payment.pg.cancel.retry";
  public static final String PAYMENT_REFUND_FAILED = "ticketrush.payment.refund.failed";
  // booking당 FAILED 이력 상한 초과로 기록을 억제한 횟수(#333).
  public static final String PAYMENT_FAILED_RECORD_SUPPRESSED =
      "ticketrush.payment.failed_record.suppressed";
  // 결제 확정 전 예매 상태 동기 확인이 PG 승인을 차단한 횟수(#490). 이 가드가 막는 건은 원래
  // "과금됐는데 좌석이 없는" 상태로 끝나던 것이라, 차단 건수가 곧 방지한 사고 건수다. 로그로는
  // 대량 만료 구간의 규모를 집계할 수 없어 이 카운터가 유일한 관측 축이다(서킷브레이커 미도입).
  // reason 태그는 네 갈래이며 모두 유한 집합이다 — booking-service BookingStatus 이름(상태 때문에
  // 막힌 건), unknown(모르는 상태 문자열·null), lookup_failed/not_found(상태 판정에 도달하지 못하고
  // 조회 단계에서 막힌 건), owner_mismatch/owner_unknown(소유자 대조에서 막힌 건, #572). 세 번째
  // 갈래를 함께 세지 않으면 booking 장애로 결제가 전건 거부되는 구간에서 이 카운터가 0으로 평평해
  // 장애가 관측되지 않는다. 네 번째 갈래는 응답이 "예매 없음"·"통신 실패"로 동일화돼 있어(#572)
  // 이 태그가 소유자 차단을 구분하는 유일한 축이다.
  public static final String PAYMENT_CONFIRM_BOOKING_GUARD_BLOCKED =
      "ticketrush.payment.confirm.booking_guard.blocked";
  // 결제 확정 경로의 booking 동기 조회 왕복 지연(#633). Timer.
  // payment의 booking RestClient는 오토컨피그된 RestClient.Builder가 아니라 생 RestClient.builder()로
  // 만들어져 ObservationRegistry가 붙지 않는다 — http_client_requests_seconds가 없어 이 구간의 지연을
  // 볼 축이 하나도 없었다(#402가 ticket-service에서 겪은 것과 같은 제약이다). 바로 위 BOOKING_GUARD_BLOCKED는
  // "막힌 건수"만 세므로 "얼마나 느렸나"는 답하지 못한다.
  // 이 Timer가 필요한 이유는 #571 서킷브레이커의 임계값이 정확히 이 구간의 지연 분포 위에서 정해지기
  // 때문이다. 근거 없이 ticket-service 값을 복사하면 오탐 open이 나는데, 이 조회는 fail-closed라 그 오탐이
  // 곧 결제 전면 중단이 된다.
  // 선례가 #496이다. ticket-service의 slowCallDurationThreshold 300ms는 **근거 없이 잡은 값이 아니라
  // #402 실측 왕복 3.20ms를 기준선으로 잡은 값**이었는데(BookingCircuitBreakerConfig javadoc), 그럼에도
  // 부하 회차의 재기동 구간에서 실패 0건에 차단 1,472건이 났다. 그 실측이 **워밍업이 끝난 상태의 값**
  // 이었기 때문이다(측정 종료 후 500ms로 교정해 배포했다).
  // 그래서 이 Timer는 정상 구간뿐 아니라 재기동 직후 구간의 분포까지 재는 것이 목적이다 — 정상 구간
  // 실측만으로는 워밍업을 덮지 못한다는 것이 #496이 남긴 교훈이다.
  // outcome 태그는 호출 결말과 1:1인 유한 집합 3종이다 — success(정상 반환), not_found(booking이 의도적으로
  // 낸 404), failed(통신 실패·타임아웃·계약 붕괴 → 503).
  // not_found를 반드시 갈라야 한다. #571이 404를 서킷 실패로 세지 않기로 했는데(없는 예매를 반복 조회하는
  // 것만으로 서킷이 열리면 멀쩡한 결제가 전부 503이 된다), 한 덩어리로 재면 실패율 임계의 근거가 되는
  // 분포에 정상 응답이 섞인다.
  public static final String PAYMENT_BOOKING_LOOKUP = "ticketrush.payment.booking.lookup"; // Timer
  // 아직 해결되지 않은 환불 실패(FAILED 환불 이력) 건수(#574). Gauge.
  // 0이 아니면 "과금이 남았는데 환불이 성사되지 않은" 건이 그만큼 있다는 뜻이라, 수치 자체가 곧
  // 미해결 사고 건수다. PAYMENT_REFUND_FAILED(발생 카운터)와 달리 이쪽은 잔량이라 해결되면 내려간다.
  public static final String PAYMENT_REFUND_UNRESOLVED =
      "ticketrush.payment.refund.unresolved"; // Gauge
  // 미해결 환불 실패의 보상 신호를 재발행한 횟수(#574). 재발행은 수신 측이 이미 처리했더라도
  // 나가므로 이 값이 곧 유실 건수는 아니다 — 유실 여부는 EVENT_PUBLISH_FAILED 쪽에서 읽는다.
  public static final String PAYMENT_REFUND_SIGNAL_REPLAYED =
      "ticketrush.payment.refund.signal.replayed";
  // 결제 확정 신호 유실로 "과금됐는데 예매가 만료된" 건을 찾아낸 횟수(#607). 이 사고는 payment·booking 어느
  // 쪽에도 미해결 표시가 남지 않아(refund_failed_at 은 EXPIRED 에서 구조적으로 채워지지 않는다) 이 카운터가
  // 발생 자체의 유일한 관측 축이다. 0 이 아니면 그만큼의 사용자 돈이 환불 없이 남아 있었다는 뜻이다.
  public static final String PAYMENT_CHARGED_EXPIRED_DETECTED =
      "ticketrush.payment.charged_expired.detected";
  // 그중 실제로 자동 환불에 성공한 횟수(#607). detected 와의 차이가 곧 미복구 잔량이라, Grafana 에서 두
  // 시계열을 겹쳐 읽는다. Gauge 로 잔량을 직접 재지 않는 것은 정확히 세려면 만료 테이블을 매번 전건 훑어야
  // 하는데 이 설계가 피하려던 것이 바로 그 풀스캔이기 때문이다(ADR 0015).
  public static final String PAYMENT_CHARGED_EXPIRED_RECOVERED =
      "ticketrush.payment.charged_expired.recovered";
  // 후보였으나 환불하지 않고 건너뛴 횟수(#607). reason 태그는 판정 단계와 1:1인 유한 집합 7종이다 —
  // already_refunded(환불이 성사·진행 중), refund_failed_history(PG 가 거절해 FAILED 이력만 남음),
  // grace(만료 직후라 정상 처리 중일 수 있음), booking_lookup_failed(booking 조회 실패 → fail-closed),
  // booking_alive(예매가 살아 있는 알려진 상태), booking_status_unknown(모르는 상태 문자열),
  // booking_number_unknown(예매번호를 얻지 못함 → 좌석 오반환 방지로 중단, #608).
  // 뒤 넷은 정상 스킵이 아니라 조사 대상이다. 태그로 갈라 두지 않으면 "환불하지 않았다"는 한 덩어리에
  // 묻혀, 사고 건이 조용히 방치되는 것과 정상 동작이 구분되지 않는다. 특히 두 쌍의 구분이 핵심이다 —
  // already_refunded 와 refund_failed_history 는 "돈이 돌아갔는가"가 정반대이고(후자는 미해결 사고인데
  // 예매가 EXPIRED 라 booking 쪽 관리자 게이트도 열리지 않아 이 태그가 유일한 창구다),
  // booking_alive 와 booking_status_unknown 은 "정상"과 "상대가 계약을 바꿔 복구가 전면 정지"의 차이다.
  //
  // ⚠️ already_refunded 는 오늘 코드에서 사실상 도달하지 않는다. 후보가 되려면 payment 가 COMPLETED 여야
  // 하는데 PaymentCancelPersister 가 환불 저장과 markCanceled 를 한 트랜잭션에 묶으므로
  // refund=COMPLETED 인 booking 은 payment 가 CANCELED 라 앞선 결제 대조에서 이미 빠진다. 그래서 이 값이
  // 0 이 아니면 "정상적으로 이미 환불된 건을 건너뛰었다"가 아니라 "환불은 성사됐는데 payment 가 CANCELED 가
  // 아닌" 찢어진 상태 신호로 읽어야 한다. 대시보드·알림 문구를 그렇게 잡는다.
  public static final String PAYMENT_CHARGED_EXPIRED_SKIPPED =
      "ticketrush.payment.charged_expired.skipped";
  // 결제 취소 API가 예매번호를 확보하지 못해 취소 자체를 차단한 횟수(#608). 이 가드가 막는 건은 원래
  // "다른 예매가 결제 완료한 SOLD 좌석을 AVAILABLE로 되돌리는" 사고로 끝나던 것이라, 차단 건수가 곧
  // 방지한 사고 건수다. 차단이 PG 취소 앞에서 일어나므로 과금·좌석·예매가 모두 그대로 남고 사용자는
  // 재시도로 회복된다 — 이 카운터가 올라가도 데이터는 깨지지 않는다는 점이 #607의 skipped와 다르다.
  // reason 태그는 조회 결과와 1:1인 유한 집합 3종이다 — not_found(booking이 의도적으로 낸 404),
  // lookup_failed(통신 실패·타임아웃·계약 붕괴 → 503), booking_number_unknown(200을 받았으나 예매번호가
  // 비어 있음. #607이 쓴 이름과 통일한다).
  // 셋을 갈라야 하는 이유는 사용자 응답이 전부 "취소 실패"로 동일화되기 때문이다. lookup_failed는
  // booking 장애지만, booking_number_unknown은 booking이 필드를 내려주지 않는다는 뜻이라 배포 사고이고
  // 그 구간에는 취소가 전건 막힌다 — 합쳐 세면 둘을 구분할 축이 사라진다.
  public static final String PAYMENT_CANCEL_BOOKING_GUARD_BLOCKED =
      "ticketrush.payment.cancel.booking_guard.blocked";

  // ticket
  public static final String TICKET_ISSUE = "ticketrush.ticket.issue";
  public static final String TICKET_ISSUE_CRITICAL_FAILURE =
      "ticketrush.ticket.issue.critical_failure";

  // kafka / outbox (횡단)
  public static final String KAFKA_INBOX = "ticketrush.kafka.inbox";
  public static final String KAFKA_DLT = "ticketrush.kafka.dlt";
  public static final String OUTBOX_RELAY = "ticketrush.outbox.relay";
  public static final String OUTBOX_BACKLOG = "ticketrush.outbox.backlog"; // Gauge
  // 발행을 띄우고 콜백을 기다리는 중인 건수(#483). backlog가 in-flight 행도 세므로 둘을 겹쳐 봐야
  // "콜백 대기(정상)"와 "릴레이 정지(장애)"가 갈린다.
  public static final String OUTBOX_IN_FLIGHT = "ticketrush.outbox.in_flight"; // Gauge
  // Kafka 발행이 브로커 응답 콜백에서 실패한 횟수(#574). 발행은 fire-and-forget이라 실패가 호출자에게
  // 전파되지 않으므로, 이 카운터가 "발행 유실이 실제로 일어나는가"를 보는 유일한 축이다. outbox 모드는
  // 릴레이가 상태로 남겨 재발행하지만 kafka 모드는 그대로 사라진다.
  public static final String EVENT_PUBLISH_FAILED = "ticketrush.event.publish.failed";

  // ===== Tag keys =====
  public static final String TAG_RESULT = "result";
  public static final String TAG_REASON = "reason";
  public static final String TAG_OUTCOME = "outcome";
  public static final String TAG_CONSUMER_GROUP = "consumer_group";
  public static final String TAG_AGGREGATE_TYPE = "aggregate_type";
  public static final String TAG_PROVIDER = "provider";
  // 이벤트를 만든 발행 경로(#520). 값은 seat-service의 SeatEventSource 열거형이 SSOT다.
  public static final String TAG_SOURCE = "source";
  // 환불을 유발한 주체(#492). 값은 payment-service의 RefundTrigger 열거형이 SSOT다. 사용자 취소와
  // 사고 보상은 발생 자체의 의미가 달라(후자는 곧 사고 건수다) 알림 임계도 따로 잡아야 하는데,
  // 이 태그가 없으면 두 건이 PAYMENT_REFUND 한 시계열에 섞여 보상 발생률을 볼 수 없다.
  public static final String TAG_TRIGGER = "trigger";
  // 발행 대상 Kafka 토픽(#574). 토픽은 코드에 상수로 고정된 유한 집합이라 태그로 안전하다.
  public static final String TAG_TOPIC = "topic";

  // ===== Tag values =====
  public static final String RESULT_SUCCESS = "success";
  public static final String RESULT_FAILURE = "failure";
  public static final String RESULT_UNAVAILABLE = "unavailable";
  public static final String RESULT_PROCESSED = "processed";
  public static final String RESULT_DUPLICATE = "duplicate";
  public static final String RESULT_FAIL = "fail";
  public static final String RESULT_ISSUED = "issued";
  public static final String RESULT_ALREADY_ISSUED = "already_issued";
  public static final String RESULT_HIT = "hit";
  public static final String RESULT_MISS = "miss";
}
