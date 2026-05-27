package com.ticketrush.shared.booking.event;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.EventUtils;
import java.time.LocalDateTime;

public record BookingExpiredEvent(Long bookingId, LocalDateTime expiredAt) implements DomainEvent {

  public static final String TOPIC = "booking-expired-topic";
  public static final String EVENT_NAME = "BookingExpiredEvent";

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
