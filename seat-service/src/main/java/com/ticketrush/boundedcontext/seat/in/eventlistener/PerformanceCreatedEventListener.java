package com.ticketrush.boundedcontext.seat.in.eventlistener;

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
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
  private final InboxService inboxService;

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
      final Long performanceId = event.performanceId();

      // Inbox로 중복 처리를 방지한다(#110). 최초 수신일 때만 기본 좌석을 생성하고 처리와 Inbox 기록을 한 트랜잭션으로 묶는다.
      boolean processed =
          inboxService.runIfFirst(
              KafkaConsumerGroup.SEAT,
              envelope,
              () -> seatFacade.createDefaultSeats(performanceId, event.totalSeats()));

      if (!processed) {
        log.info(
            "이미 처리된 공연 생성 이벤트(inbox 중복 스킵). eventId: {}, performanceId: {}",
            envelope.eventId(),
            performanceId);
      }

      // 성공 시에만 오프셋을 커밋한다(#269 표준).
      ack.acknowledge();
    } catch (DuplicateEventException e) {
      // 동시 중복 수신으로 inbox unique가 경합함 → 멱등(중복) 정상으로 간주하고 커밋한다(#110).
      log.info("동시 중복 수신(inbox unique 경합). eventId: {}", envelope.eventId());
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
