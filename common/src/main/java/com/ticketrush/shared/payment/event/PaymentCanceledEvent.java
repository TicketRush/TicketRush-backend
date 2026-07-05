package com.ticketrush.shared.payment.event;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.EventUtils;
import java.time.LocalDateTime;

/**
 * 환불(결제 취소) 성공 시 payment-service가 발행하는 이벤트. seat/booking/ticket이 수신해 후속 정합을 처리한다.
 *
 * <p>{@code bookingNumber}는 좌석 소유 교차검증(ABA 방지)용이며, 이벤트 기반 환불 경로({@code RefundRequestedEvent})에서만
 * 채워진다. 결제 취소 API(#22) 경로는 payment가 bookingNumber를 알지 못하므로 {@code null}이다 (#91).
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
