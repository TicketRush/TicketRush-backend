package com.ticketrush.shared.booking.event;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.EventUtils;
import java.time.LocalDateTime;

/**
 * 사용자가 결제 완료(CONFIRMED) 예매를 취소해 환불이 필요할 때 booking-service가 발행하는 이벤트 (#91).
 *
 * <p>정방향 Saga의 시작점으로, payment-service가 수신해 PG 환불을 실행한다. 환불 성공 시 {@code PaymentCanceledEvent}, 실패 시
 * {@code RefundFailedEvent}로 이어진다. {@code bookingNumber}는 좌석 소유 교차검증(ABA 방지)을 위해 payment가 {@code
 * PaymentCanceledEvent}로 재전파한다.
 */
public record RefundRequestedEvent(
    Long bookingId, String bookingNumber, Long seatId, Long userId, LocalDateTime requestedAt)
    implements DomainEvent {

  public static final String TOPIC = "refund-requested-topic";
  public static final String EVENT_NAME = "RefundRequested";

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
    return String.valueOf(bookingId);
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
