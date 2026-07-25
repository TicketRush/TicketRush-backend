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
