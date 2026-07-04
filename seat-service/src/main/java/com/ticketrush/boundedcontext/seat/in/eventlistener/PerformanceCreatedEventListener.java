package com.ticketrush.boundedcontext.seat.in.eventlistener;

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
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

  private final SeatFacade seatFacade;
  private final JsonConverter jsonConverter;

  @KafkaListener(topics = PerformanceCreatedEvent.TOPIC, groupId = KafkaConsumerGroup.SEAT)
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

      // 성공 시에만 오프셋을 커밋한다(#269 표준).
      ack.acknowledge();
    } catch (Exception e) {
      // #269 표준: 영구(비즈니스/결정적) 실패는 로그 후 ack, 일시(인프라) 실패는 re-throw 하여 재시도→DLT로 보존.
      if (KafkaConsumerErrorPolicy.isPermanent(e)) {
        if (KafkaConsumerErrorPolicy.isExpectedConflict(e)) {
          log.warn("공연 생성 이벤트 처리 중 예상된 상태충돌(멱등 처리). eventId: {}", envelope.eventId(), e);
        } else {
          log.error(
              "[CRITICAL] 공연 생성 이벤트 처리 중 치명적 오류 발생! 확인이 필요합니다. eventId: {}", envelope.eventId(), e);
        }
        ack.acknowledge();
      } else {
        log.warn("공연 생성 이벤트 처리 중 일시적 오류. 재시도합니다. eventId: {}", envelope.eventId(), e);
        throw e;
      }
    }
  }
}
