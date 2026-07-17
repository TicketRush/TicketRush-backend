package com.ticketrush.global.event;

import java.time.Instant;
import java.util.UUID;

public record DomainEventEnvelope(
    String eventId,
    String eventType,
    Instant createdAt,
    String topic,
    String payload,
    String traceId) {

  public static DomainEventEnvelope of(DomainEvent event, String payload) {
    return new DomainEventEnvelope(
        UUID.randomUUID().toString(),
        event.eventName(),
        Instant.now(),
        event.topic(),
        payload,
        event.traceId());
  }
}
