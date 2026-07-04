package com.ticketrush.boundedcontext.ticket.in.eventlistener;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketIssueResponse;
import com.ticketrush.boundedcontext.ticket.app.usecase.TicketIssueUseCase;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.payment.event.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentConfirmedEventListener {

  private final TicketIssueUseCase ticketIssueUseCase;
  private final JsonConverter jsonConverter;

  @KafkaListener(topics = PaymentConfirmedEvent.TOPIC, groupId = KafkaConsumerGroup.TICKET)
  public void handlePaymentConfirmed(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {

    PaymentConfirmedEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), PaymentConfirmedEvent.class);

      log.info("결제 완료 이벤트 수신. 티켓 발급 처리. bookingId: {}", event.bookingId());

      // 티켓 발급에는 bookingId만 사용한다.
      // 중복 수신은 TicketIssueUseCase의 멱등 처리(alreadyIssued)로 안전하게 처리된다.
      TicketIssueResponse response = ticketIssueUseCase.execute(event.bookingId());

      // 신규 발급/이미 발급 여부를 남겨 멱등 동작을 추적할 수 있게 한다.
      log.info("티켓 발급 처리 완료. bookingId: {}, issued: {}", event.bookingId(), response.issued());

      // 성공 시에만 오프셋을 커밋한다(#269 표준).
      ack.acknowledge();

    } catch (Exception e) {
      // #269 표준: 영구(비즈니스/결정적) 실패는 로그 후 ack, 일시(인프라) 실패는 re-throw 하여 재시도→DLT로 보존.
      // 역직렬화 실패 시 event가 null이라 bookingId를 못 얻으므로, 항상 살아있는
      // envelope.eventId()를 함께 남겨 어떤 메시지가 실패했는지 추적할 수 있게 한다.
      Long failedBookingId = (event != null) ? event.bookingId() : null;

      if (KafkaConsumerErrorPolicy.isPermanent(e)) {
        if (KafkaConsumerErrorPolicy.isExpectedConflict(e)) {
          // 재수신 등으로 자연 발생하는 상태충돌(멱등). 정상 흐름이므로 낮은 레벨로 남긴다.
          log.warn(
              "결제 완료 이벤트 처리 중 예상된 상태충돌(멱등 처리). eventId: {}, bookingId: {}",
              envelope.eventId(),
              failedBookingId,
              e);
        } else {
          log.error(
              "[CRITICAL] 결제 완료 이벤트로 티켓 발급 중 치명적 오류 발생! "
                  + "결제는 완료되었으나 티켓 발급에 실패했습니다. 확인이 필요합니다. eventId: {}, bookingId: {}",
              envelope.eventId(),
              failedBookingId,
              e);
        }
        // 영구 실패는 재시도해도 결과가 바뀌지 않으므로 커밋해 파티션 블로킹을 막는다.
        ack.acknowledge();
      } else {
        // 일시 실패는 ack 하지 않고 예외를 다시 던져 컨테이너의 재시도→DLT 파이프라인에 위임한다.
        log.warn(
            "결제 완료 이벤트 처리 중 일시적 오류. 재시도합니다. eventId: {}, bookingId: {}",
            envelope.eventId(),
            failedBookingId,
            e);
        throw e;
      }
    }
  }
}
