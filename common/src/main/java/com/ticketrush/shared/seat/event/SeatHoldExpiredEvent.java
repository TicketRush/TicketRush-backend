package com.ticketrush.shared.seat.event;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.EventUtils;
import java.time.LocalDateTime;

public record SeatHoldExpiredEvent(Long seatId, String bookingNumber, LocalDateTime expiredAt)
    implements DomainEvent {

  public static final String TOPIC = "seat-hold-expired-topic";
  public static final String EVENT_NAME = "SeatHoldExpiredEvent";

  @Override
  public String topic() {
    return TOPIC;
  }

  @Override
  public String key() {
    return bookingNumber;
  }

  @Override
  public String aggregateId() {
    return String.valueOf(seatId);
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
