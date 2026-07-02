package com.ticketrush.shared.seat.event;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.EventUtils;

public record SeatHoldFailedEvent(Long bookingId, Long seatId, String reason)
    implements DomainEvent {

  @Override
  public String topic() {
    return "seat-hold-failed-topic";
  }

  @Override
  public String key() {
    return String.valueOf(bookingId);
  }

  @Override
  public String aggregateId() {
    return String.valueOf(seatId);
  }

  @Override
  public String eventName() {
    return "SeatHoldFailedEvent";
  }

  @Override
  public String traceId() {
    return EventUtils.extractTraceId();
  }
}
