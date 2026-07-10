package com.ticketrush.boundedcontext.ticket.in.eventlistener;

import com.ticketrush.boundedcontext.ticket.app.usecase.TicketIssueUseCase;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.payment.event.PaymentConfirmedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
  private final InboxService inboxService;
  private final MeterRegistry meterRegistry;

  @KafkaListener(topics = PaymentConfirmedEvent.TOPIC, groupId = KafkaConsumerGroup.TICKET)
  public void handlePaymentConfirmed(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {

    PaymentConfirmedEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), PaymentConfirmedEvent.class);
      final Long bookingId = event.bookingId();
      final Long userId = event.userId();

      log.info("결제 완료 이벤트 수신. 티켓 발급 처리. bookingId: {}", bookingId);

      // userId가 없으면 티켓은 발급되지만 소유권 확인에 실패해 소유자조차 QR을 조회할 수 없다(#364).
      // 발급 자체는 막지 않되(입장 게이트는 booking 동기 조회로 동작), 사후 추적이 가능하도록 남긴다.
      if (userId == null) {
        log.warn("결제 완료 이벤트에 userId가 없습니다. QR 조회가 불가능한 티켓이 발급됩니다. bookingId: {}", bookingId);
      }

      // Inbox로 중복 처리를 방지한다(#110). 최초 수신일 때만 발급하고 처리와 Inbox 기록을 한 트랜잭션으로 묶는다.
      // userId는 티켓에 복제 저장해 QR 조회가 booking-service 동기 호출 없이 소유권을 확인하게 한다(#364).
      // 이미 발급된 케이스는 TicketIssueUseCase가 멱등하게(alreadyIssued) 처리한다.
      boolean processed =
          inboxService.runIfFirst(
              KafkaConsumerGroup.TICKET,
              envelope,
              () -> ticketIssueUseCase.execute(bookingId, userId));

      if (processed) {
        log.info("티켓 발급 처리 완료. bookingId: {}", bookingId);
      } else {
        log.info(
            "이미 처리된 결제 완료 이벤트(inbox 중복 스킵). eventId: {}, bookingId: {}",
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
          Counter.builder(MetricNames.TICKET_ISSUE_CRITICAL_FAILURE)
              .register(meterRegistry)
              .increment();
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
