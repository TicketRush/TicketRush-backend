package com.ticketrush.boundedcontext.ticket.in.eventlistener;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketIssueResponse;
import com.ticketrush.boundedcontext.ticket.app.usecase.TicketIssueUseCase;
import com.ticketrush.global.event.DomainEventEnvelope;
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

  @KafkaListener(topics = PaymentConfirmedEvent.TOPIC, groupId = "ticket-group")
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

    } catch (Exception e) {
      // 결제는 이미 완료된 상태라 재시도해도 결과가 바뀌지 않는다.
      // 무한 재시도/파티션 블로킹을 피하고, 강한 로그를 남겨 수동 복구가 가능하도록 조치한다.
      // 역직렬화 실패 시 event가 null이라 bookingId를 못 얻으므로, 항상 살아있는
      // envelope.eventId()를 함께 남겨 어떤 메시지가 실패했는지 추적할 수 있게 한다.
      Long failedBookingId = (event != null) ? event.bookingId() : null;
      log.error(
          "[CRITICAL] 결제 완료 이벤트로 티켓 발급 중 치명적 오류 발생! "
              + "결제는 완료되었으나 티켓 발급에 실패했습니다. 확인이 필요합니다. eventId: {}, bookingId: {}",
          envelope.eventId(),
          failedBookingId,
          e);

      // TODO: 추후 고도화 시, 이 위치에서 DLT로 실패한 이벤트를 재발행

    } finally {
      // 카프카 메시지가 무한 반복되며 파티션을 막는 현상 방지
      ack.acknowledge();
    }
  }
}
