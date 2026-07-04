package com.ticketrush.boundedcontext.ticket.in.eventlistener;

import com.ticketrush.boundedcontext.ticket.app.usecase.TicketCancelUseCase;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCanceledEventListener {

  private final TicketCancelUseCase ticketCancelUseCase;
  private final JsonConverter jsonConverter;
  private final InboxService inboxService;

  @KafkaListener(topics = PaymentCanceledEvent.TOPIC, groupId = KafkaConsumerGroup.TICKET)
  public void handlePaymentCanceled(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {

    PaymentCanceledEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), PaymentCanceledEvent.class);
      final Long bookingId = event.bookingId();

      log.info("결제 취소 이벤트 수신. 입장권 취소 처리. bookingId: {}", bookingId);

      // Inbox로 중복 처리를 방지한다(#110). 최초 수신일 때만 취소를 수행하고 처리와 Inbox 기록을 한 트랜잭션으로 묶는다.
      // 입장권 취소에는 bookingId만 사용한다. USED 등 비정상 케이스는 TicketCancelUseCase가 멱등하게(로그만) 처리한다.
      boolean processed =
          inboxService.runIfFirst(
              KafkaConsumerGroup.TICKET, envelope, () -> ticketCancelUseCase.execute(bookingId));

      if (processed) {
        log.info("입장권 취소 처리 완료. bookingId: {}", bookingId);
      } else {
        log.info(
            "이미 처리된 결제 취소 이벤트(inbox 중복 스킵). eventId: {}, bookingId: {}",
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
      // 역직렬화 실패 시 event가 null이라 bookingId를 못 얻으므로, 항상 살아있는
      // envelope.eventId()를 함께 남겨 어떤 메시지가 실패했는지 추적할 수 있게 한다.
      Long failedBookingId = (event != null) ? event.bookingId() : null;

      if (KafkaConsumerErrorPolicy.isPermanent(e)) {
        if (KafkaConsumerErrorPolicy.isExpectedConflict(e)) {
          log.warn(
              "결제 취소 이벤트 처리 중 예상된 상태충돌(멱등 처리). eventId: {}, bookingId: {}",
              envelope.eventId(),
              failedBookingId,
              e);
        } else {
          log.error(
              "[CRITICAL] 결제 취소 이벤트로 입장권 취소 중 치명적 오류 발생! "
                  + "결제는 취소되었으나 입장권 취소에 실패했습니다. 확인이 필요합니다. eventId: {}, bookingId: {}",
              envelope.eventId(),
              failedBookingId,
              e);
        }
        ack.acknowledge();
      } else {
        log.warn(
            "결제 취소 이벤트 처리 중 일시적 오류. 재시도합니다. eventId: {}, bookingId: {}",
            envelope.eventId(),
            failedBookingId,
            e);
        throw e;
      }
    }
  }
}
