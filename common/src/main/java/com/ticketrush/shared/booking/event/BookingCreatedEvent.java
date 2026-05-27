package com.ticketrush.shared.booking.event;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.EventUtils;

public record BookingCreatedEvent(
    Long bookingId, String bookingNumber, Long seatId, Long performanceId, Long userId)
    implements DomainEvent {

  @Override
  public String topic() {
    return "booking-created-topic";
  }

  @Override
  public String key() {
    return String.valueOf(bookingId);
  }

  @Override
  public String eventName() {
    return "BookingCreatedEvent";
  }

  @Override
  public String traceId() {
    return EventUtils.extractTraceId();
  }
}
