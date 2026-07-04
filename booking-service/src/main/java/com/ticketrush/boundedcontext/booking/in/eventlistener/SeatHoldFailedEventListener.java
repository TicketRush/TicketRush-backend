package com.ticketrush.boundedcontext.booking.in.eventlistener;

import com.ticketrush.boundedcontext.booking.app.usecase.BookingCancelUseCase;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
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

  @KafkaListener(topics = SeatHoldFailedEvent.TOPIC, groupId = KafkaConsumerGroup.BOOKING)
  public void handleSeatHoldFailed(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {

    SeatHoldFailedEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), SeatHoldFailedEvent.class);

      log.warn(
          "좌석 선점 실패 이벤트 수신. 보상 트랜잭션 실행. bookingId: {}, 사유: {}", event.bookingId(), event.reason());

      bookingCancelUseCase.execute(event.bookingId());

      // 성공 시에만 오프셋을 커밋한다(#269 표준).
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
