package com.ticketrush.boundedcontext.seat.in.eventlistener;

import com.ticketrush.boundedcontext.seat.app.usecase.SeatReleaseSoldSeatUseCase;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.payment.event.PaymentCanceledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 결제 취소(환불 성공) 이벤트를 수신해 좌석을 반환한다 (#49).
 *
 * <p>환불 성공을 뜻하는 {@code PaymentCanceledEvent}에만 좌석 반환을 매달아, "환불 성공 이후에만 좌석 반환"(refund-first)을 보장한다.
 * 이로써 환불됐는데 좌석이 SOLD로 남는 역방향 정합성 공백을 닫는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCanceledEventListener {

  private final SeatReleaseSoldSeatUseCase seatReleaseSoldSeatUseCase;
  private final JsonConverter jsonConverter;
  private final InboxService inboxService;

  @KafkaListener(topics = PaymentCanceledEvent.TOPIC, groupId = KafkaConsumerGroup.SEAT)
  public void handlePaymentCanceled(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {
    PaymentCanceledEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), PaymentCanceledEvent.class);
      final Long seatId = event.seatId();

      // Inbox로 중복 처리를 방지한다(#110). 최초 수신일 때만 좌석 반환을 수행하고 처리와 Inbox 기록을 한 트랜잭션으로 묶는다.
      boolean processed =
          inboxService.runIfFirst(
              KafkaConsumerGroup.SEAT, envelope, () -> seatReleaseSoldSeatUseCase.execute(seatId));

      if (!processed) {
        log.info(
            "이미 처리된 결제 취소 이벤트(inbox 중복 스킵). eventId: {}, seatId: {}", envelope.eventId(), seatId);
      }

      // 성공 시에만 오프셋을 커밋한다(#269 표준).
      ack.acknowledge();

    } catch (DuplicateEventException e) {
      // 동시 중복 수신으로 inbox unique가 경합함 → 멱등(중복) 정상으로 간주하고 커밋한다(#110).
      log.info("동시 중복 수신(inbox unique 경합). eventId: {}", envelope.eventId());
      ack.acknowledge();
    } catch (Exception e) {
      // #269 표준: 영구(비즈니스/결정적) 실패는 로그 후 ack, 일시(인프라) 실패는 re-throw 하여 재시도→DLT로 보존.
      Long seatId = event != null ? event.seatId() : null;

      if (KafkaConsumerErrorPolicy.isPermanent(e)) {
        if (KafkaConsumerErrorPolicy.isExpectedConflict(e)) {
          log.warn("결제 취소 좌석 반환 이벤트 처리 중 예상된 상태충돌(멱등 처리). seatId: {}", seatId, e);
        } else {
          log.error("[CRITICAL] 결제 취소 좌석 반환 이벤트 처리 실패! 확인이 필요합니다. seatId: {}", seatId, e);
        }
        ack.acknowledge();
      } else {
        log.warn("결제 취소 좌석 반환 이벤트 처리 중 일시적 오류. 재시도합니다. seatId: {}", seatId, e);
        throw e;
      }
    }
  }
}
