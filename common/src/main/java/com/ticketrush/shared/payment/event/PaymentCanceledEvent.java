package com.ticketrush.shared.payment.event;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.EventUtils;
import java.time.LocalDateTime;

/**
 * 환불(결제 취소) 성공 시 payment-service가 발행하는 이벤트. seat/booking/ticket이 수신해 후속 정합을 처리한다.
 *
 * <p>{@code bookingNumber}는 좌석 소유 교차검증(ABA 방지)용이며 <b>모든 발행 경로에서 채워진다</b>(#608). seat가 이 값으로 "그 좌석이
 * 아직 이 예매의 것인지"를 대조하므로, 값 없이 발행하면 그 대조가 통째로 꺼져 <b>다른 예매가 결제 완료한 SOLD 좌석을 AVAILABLE로 되돌린다</b>.
 *
 * <p>결제 취소 API(#22) 경로는 payment가 예매번호를 자신의 테이블에 갖고 있지 않아 booking 내부 조회로 얻는다. 얻지 못하면 PG 취소
 * <b>앞에서</b> 취소를 차단하므로 이 이벤트 자체가 발행되지 않는다(#608) — 즉 이 필드가 빈 이벤트는 계약상 나오지 않으며, 수신 측은 비어 있는 값을 "검증
 * 생략"이 아니라 <b>계약 파기</b>로 다뤄야 한다(#91에서 이어진 규율).
 */
public record PaymentCanceledEvent(
    Long paymentId,
    Long bookingId,
    String bookingNumber,
    Long seatId,
    Long refundId,
    Long refundedAmount,
    String reason,
    LocalDateTime canceledAt)
    implements DomainEvent {

  public static final String TOPIC = "payment-canceled-topic";
  public static final String EVENT_NAME = "PaymentCanceled";

  @Override
  public String topic() {
    return TOPIC;
  }

  @Override
  public String key() {
    return String.valueOf(bookingId);
  }

  @Override
  public String aggregateId() {
    return String.valueOf(paymentId);
  }

  @Override
  public String eventName() {
    return EVENT_NAME;
  }

  @Override
  public String traceId() {
    return EventUtils.extractTraceId();
  }
}
