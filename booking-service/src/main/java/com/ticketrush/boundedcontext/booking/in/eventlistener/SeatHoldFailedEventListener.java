package com.ticketrush.boundedcontext.booking.in.eventlistener;

import com.ticketrush.boundedcontext.booking.app.usecase.BookingCancelUseCase;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.seat.event.SeatHoldFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeatHoldFailedEventListener {

  private final BookingCancelUseCase bookingCancelUseCase;
  private final JsonConverter jsonConverter;
  private final InboxService inboxService;

  @KafkaListener(topics = SeatHoldFailedEvent.TOPIC, groupId = KafkaConsumerGroup.BOOKING)
  public void handleSeatHoldFailed(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {

    SeatHoldFailedEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), SeatHoldFailedEvent.class);
      final Long bookingId = event.bookingId();

      log.warn("좌석 선점 실패 이벤트 수신. 보상 트랜잭션 실행. bookingId: {}, 사유: {}", bookingId, event.reason());

      // Inbox로 중복 처리를 방지한다(#110). 최초 수신일 때만 예매 취소를 수행하고 처리와 Inbox 기록을 한 트랜잭션으로 묶는다.
      boolean processed =
          inboxService.runIfFirst(
              KafkaConsumerGroup.BOOKING, envelope, () -> bookingCancelUseCase.execute(bookingId));

      if (!processed) {
        log.info(
            "이미 처리된 좌석 선점 실패 이벤트(inbox 중복 스킵). eventId: {}, bookingId: {}",
            envelope.eventId(),
            bookingId);
      }

      // 성공 시에만 오프셋을 커밋한다(#269 표준).
      ack.acknowledge();

    } catch (DuplicateEventException e) {
      // 동시 중복 수신으로 inbox unique가 경합함 → 멱등(중복) 정상으로 간주하고 커밋한다(#110).
      log.info("동시 중복 수신(inbox unique 경합). eventId: {}", envelope.eventId());
      ack.acknowledge();
    } catch (Exception e) {
      // #269 표준: 영구(비즈니스/결정적) 실패는 로그 후 ack, 일시(인프라) 실패는 re-throw 하여 재시도→DLT로 보존.
      Long failedBookingId = (event != null) ? event.bookingId() : null;

      if (KafkaConsumerErrorPolicy.isPermanent(e)) {
        if (KafkaConsumerErrorPolicy.isExpectedConflict(e)) {
          log.warn("좌석 선점 실패 보상 처리 중 예상된 상태충돌(멱등 처리). bookingId: {}", failedBookingId, e);
        } else {
          log.error(
              "[CRITICAL] 좌석 선점 실패에 대한 예매 취소 중 치명적 오류 발생! "
                  + "데이터 정합성이 깨졌습니다. 확인이 필요합니다. bookingId: {}",
              failedBookingId,
              e);
        }
        ack.acknowledge();
      } else {
        log.warn("좌석 선점 실패 보상 처리 중 일시적 오류. 재시도합니다. bookingId: {}", failedBookingId, e);
        throw e;
      }
    }
  }
}
