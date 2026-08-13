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
