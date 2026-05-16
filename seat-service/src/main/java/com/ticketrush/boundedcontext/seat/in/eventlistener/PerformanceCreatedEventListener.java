package com.ticketrush.boundedcontext.seat.in.eventlistener;

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.performance.event.PerformanceCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceCreatedEventListener {

  private static final String SEAT_GROUP_ID = "seat-group";

  private final SeatFacade seatFacade;
  private final JsonConverter jsonConverter;

  @KafkaListener(topics = PerformanceCreatedEvent.TOPIC, groupId = SEAT_GROUP_ID)
  public void handlePerformanceCreated(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {
    try {
      if (!PerformanceCreatedEvent.EVENT_NAME.equals(envelope.eventType())) {
        log.info("처리 대상이 아닌 공연 이벤트입니다. eventType: {}", envelope.eventType());
        ack.acknowledge();
        return;
      }

      PerformanceCreatedEvent event =
          jsonConverter.deserialize(envelope.payload(), PerformanceCreatedEvent.class);

      seatFacade.createDefaultSeats(event.performanceId());
      ack.acknowledge();
    } catch (Exception e) {
      log.error("공연 생성 이벤트 처리 중 에러가 발생했습니다. eventId: {}", envelope.eventId(), e);
      throw e;
    }
  }
}
