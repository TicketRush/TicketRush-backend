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

  // payment
  public static final String PAYMENT_CONFIRM = "ticketrush.payment.confirm";
  public static final String PAYMENT_PG_APPROVE = "ticketrush.payment.pg.approve"; // Timer
  public static final String PAYMENT_REFUND = "ticketrush.payment.refund";
  public static final String PAYMENT_PG_CANCEL = "ticketrush.payment.pg.cancel"; // Timer
  public static final String PAYMENT_REFUND_FAILED = "ticketrush.payment.refund.failed";
  // booking당 FAILED 이력 상한 초과로 기록을 억제한 횟수(#333).
  public static final String PAYMENT_FAILED_RECORD_SUPPRESSED =
      "ticketrush.payment.failed_record.suppressed";

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

  // ===== Tag values =====
  public static final String RESULT_SUCCESS = "success";
  public static final String RESULT_FAILURE = "failure";
  public static final String RESULT_UNAVAILABLE = "unavailable";
  public static final String RESULT_PROCESSED = "processed";
  public static final String RESULT_DUPLICATE = "duplicate";
  public static final String RESULT_FAIL = "fail";
  public static final String RESULT_ISSUED = "issued";
  public static final String RESULT_ALREADY_ISSUED = "already_issued";
}
