package com.ticketrush.boundedcontext.seat.in.eventlistener;

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.booking.event.BookingCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingCreatedEventListener {

  private final SeatFacade seatFacade;
  private final JsonConverter jsonConverter;
  private final InboxService inboxService;

  @KafkaListener(topics = BookingCreatedEvent.TOPIC, groupId = KafkaConsumerGroup.SEAT)
  public void handleBookingCreated(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {

    BookingCreatedEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), BookingCreatedEvent.class);
      final BookingCreatedEvent bookingCreatedEvent = event;

      log.info(
          "예매 생성 이벤트 수신. 좌석 선점 처리. bookingId: {}, seatId: {}",
          bookingCreatedEvent.bookingId(),
          bookingCreatedEvent.seatId());

      // Inbox로 중복 처리를 방지한다(#110). 최초 수신일 때만 좌석 선점을 시도하고, 처리(HOLD 또는 보상 이벤트 발행)와
      // Inbox 기록을 한 트랜잭션으로 원자 커밋한다. Redisson 락은 SeatFacade 내부에서 유지된다(레거시 SETNX 멱등키는 Inbox로 대체).
      boolean processed =
          inboxService.runIfFirst(
              KafkaConsumerGroup.SEAT,
              envelope,
              () ->
                  seatFacade.tryLockSeat(
                      bookingCreatedEvent.bookingId(),
                      bookingCreatedEvent.bookingNumber(),
                      bookingCreatedEvent.seatId(),
                      bookingCreatedEvent.userId()));

      if (processed) {
        log.info("좌석 선점 처리 완료. bookingId: {}", bookingCreatedEvent.bookingId());
      } else {
        log.info(
            "이미 처리된 예매 생성 이벤트(inbox 중복 스킵). eventId: {}, bookingId: {}",
            envelope.eventId(),
            bookingCreatedEvent.bookingId());
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
          log.warn(
              "예매 생성 이벤트 처리 중 예상된 상태충돌(멱등 처리). eventId: {}, bookingId: {}",
              envelope.eventId(),
              failedBookingId,
              e);
        } else {
          log.error(
              "[CRITICAL] 예매 생성 이벤트로 좌석 선점 중 치명적 오류 발생! 확인이 필요합니다. eventId: {}, bookingId: {}",
              envelope.eventId(),
              failedBookingId,
              e);
        }
        // 영구 실패는 재시도해도 결과가 바뀌지 않으므로 커밋해 파티션 블로킹을 막는다.
        ack.acknowledge();
      } else {
        // 일시 실패는 ack 하지 않고 예외를 다시 던져 컨테이너의 재시도→DLT 파이프라인에 위임한다.
        log.warn(
            "예매 생성 이벤트 처리 중 일시적 오류. 재시도합니다. eventId: {}, bookingId: {}",
            envelope.eventId(),
            failedBookingId,
            e);
        throw e;
      }
    }
  }
}
