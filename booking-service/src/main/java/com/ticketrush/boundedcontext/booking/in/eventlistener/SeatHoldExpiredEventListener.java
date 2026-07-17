package com.ticketrush.boundedcontext.booking.in.eventlistener;

import com.ticketrush.boundedcontext.booking.app.usecase.ExpireBookingByNumberUseCase;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.seat.event.SeatHoldExpiredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeatHoldExpiredEventListener {

  private final ExpireBookingByNumberUseCase expireBookingByNumberUseCase;
  private final JsonConverter jsonConverter;
  private final InboxService inboxService;

  @KafkaListener(topics = SeatHoldExpiredEvent.TOPIC, groupId = KafkaConsumerGroup.BOOKING)
  public void handleSeatHoldExpired(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {

    SeatHoldExpiredEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), SeatHoldExpiredEvent.class);
      final SeatHoldExpiredEvent expiredEvent = event;

      log.info(
          "좌석 hold 만료 이벤트 수신. bookingNumber: {}, seatId: {}",
          expiredEvent.bookingNumber(),
          expiredEvent.seatId());

      // Inbox로 중복 처리를 방지한다(#110). 최초 수신일 때만 예매 만료를 수행하고 처리와 Inbox 기록을 한 트랜잭션으로 묶는다.
      boolean processed =
          inboxService.runIfFirst(
              KafkaConsumerGroup.BOOKING,
              envelope,
              () ->
                  expireBookingByNumberUseCase.execute(
                      expiredEvent.bookingNumber(), expiredEvent.expiredAt()));

      if (!processed) {
        log.info(
            "이미 처리된 좌석 hold 만료 이벤트(inbox 중복 스킵). eventId: {}, bookingNumber: {}",
            envelope.eventId(),
            expiredEvent.bookingNumber());
      }

      // 성공 시에만 오프셋을 커밋한다(#269 표준).
      ack.acknowledge();

    } catch (DuplicateEventException e) {
      // 동시 중복 수신으로 inbox unique가 경합함 → 멱등(중복) 정상으로 간주하고 커밋한다(#110).
      log.info("동시 중복 수신(inbox unique 경합). eventId: {}", envelope.eventId());
      ack.acknowledge();
    } catch (Exception e) {
      // #269 표준: 영구(비즈니스/결정적) 실패는 로그 후 ack, 일시(인프라) 실패는 re-throw 하여 재시도→DLT로 보존.
      String failedBookingNumber = (event != null) ? event.bookingNumber() : null;

      if (KafkaConsumerErrorPolicy.isPermanent(e)) {
        if (KafkaConsumerErrorPolicy.isExpectedConflict(e)) {
          log.warn("좌석 hold 만료 처리 중 예상된 상태충돌(멱등 처리). bookingNumber: {}", failedBookingNumber, e);
        } else {
          log.error(
              "[CRITICAL] 좌석 hold 만료에 대한 예매 EXPIRED 전이 중 치명적 오류 발생! "
                  + "데이터 정합성 확인이 필요합니다. bookingNumber: {}",
              failedBookingNumber,
              e);
        }
        ack.acknowledge();
      } else {
        log.warn("좌석 hold 만료 처리 중 일시적 오류. 재시도합니다. bookingNumber: {}", failedBookingNumber, e);
        throw e;
      }
    }
  }
}
