package com.ticketrush.shared.payment.event;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.EventUtils;
import java.time.LocalDateTime;

public record PaymentCanceledEvent(
    Long paymentId,
    Long bookingId,
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
  public String eventName() {
    return EVENT_NAME;
  }

  @Override
  public String traceId() {
    return EventUtils.extractTraceId();
  }
}
