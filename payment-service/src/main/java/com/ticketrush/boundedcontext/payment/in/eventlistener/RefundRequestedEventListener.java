package com.ticketrush.boundedcontext.payment.in.eventlistener;

import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentRefundByBookingUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentRefundByBookingUseCase.RefundOutcome;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.RefundRequestedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 예매 취소로 발행된 {@link RefundRequestedEvent}를 수신해 PG 환불을 실행한다 (#91, 정방향 Saga).
 *
 * <p>성공 시 {@code PaymentCanceledEvent}가 발행되어 seat/booking/ticket이 정합을 완료한다. 실패는 원인에 따라 갈린다:
 *
 * <ul>
 *   <li><b>PG 거절({@link ErrorStatus#PAYMENT_REFUND_FAILED}, 4xx)</b> = 결정적 실패 → {@code
 *       RefundFailedEvent}로 booking을 REFUND_FAILED로 보상하고 ack 한다(재시도 무의미).
 *   <li><b>PG 통신 실패({@link ErrorStatus#PAYMENT_PG_COMMUNICATION_FAILED}, 5xx/timeout)</b> = 일시적 실패
 *       → 재시도→DLT로 위임한다. 환불 성공 여부가 불명이라 섣불리 REFUND_FAILED로 확정하지 않는다(멱등 키로 재시도는 안전).
 *   <li><b>동시/중복 환불(unique 경합, #296)</b> = 이미 환불됨 → 멱등 ack(보상 아님).
 *   <li><b>그 외(역직렬화 등)</b> = 재시도→DLT로 보존한다.
 * </ul>
 *
 * <p><b>Inbox(#110) 미사용 근거</b>: PG 취소는 트랜잭션 밖 외부 부수효과라 {@code InboxService.runIfFirst}의 단일 트랜잭션에 담기
 * 부적합하다. 멱등은 결제 상태 선검사(COMPLETED만 환불) + {@code Refund.paymentId} unique(#296) + PG 고정 멱등 키({@code
 * REFUND-%07d})로 보장한다.
 *
 * <p><b>#269 표준 분류를 그대로 쓰지 않는 근거</b>: PG 통신 실패도 {@code BusinessException}이라 {@code
 * KafkaConsumerErrorPolicy.isPermanent}로는 "보상할 결정적 실패(PG 거절)"와 "재시도할 일시 실패(통신 오류)"를 가를 수 없다. 그래서 PG
 * 거절({@code PAYMENT_REFUND_FAILED})만 {@code ErrorStatus}로 직접 식별해 보상하고, 나머지는 재시도→DLT로 넘긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundRequestedEventListener {

  private static final String REASON_REFUND_FAILED = "PG 환불 처리 실패";

  private final PaymentRefundByBookingUseCase paymentRefundByBookingUseCase;
  private final PaymentEventPublisher paymentEventPublisher;
  private final JsonConverter jsonConverter;
  private final MeterRegistry meterRegistry;

  @KafkaListener(topics = RefundRequestedEvent.TOPIC, groupId = KafkaConsumerGroup.PAYMENT)
  public void handleRefundRequested(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {
    RefundRequestedEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), RefundRequestedEvent.class);

      RefundOutcome outcome = paymentRefundByBookingUseCase.execute(event);

      if (outcome == RefundOutcome.REPUBLISHED) {
        log.info(
            "이미 환불된 건이라 PaymentCanceledEvent를 재발행(self-heal). eventId: {}, bookingId: {}",
            envelope.eventId(),
            event.bookingId());
      } else if (outcome == RefundOutcome.ALREADY_SETTLED) {
        log.info(
            "대상 결제가 없어 환불 요청을 멱등 스킵. eventId: {}, bookingId: {}",
            envelope.eventId(),
            event.bookingId());
      }

      Counter.builder(MetricNames.PAYMENT_REFUND)
          .tag(MetricNames.TAG_OUTCOME, outcome.name().toLowerCase())
          .register(meterRegistry)
          .increment();

      // 성공(재발행·멱등 스킵 포함) 시에만 오프셋을 커밋한다(#269 표준).
      ack.acknowledge();
    } catch (DataIntegrityViolationException e) {
      // 동시 환불이 unique 제약(#296)에 막힌 경우 = 이미 다른 트랜잭션에서 환불 확정됨. 멱등 정상으로 간주하고 커밋한다(보상 아님).
      log.info(
          "동시 중복 환불(unique 경합). eventId: {}, bookingId: {}",
          envelope.eventId(),
          event != null ? event.bookingId() : null);
      ack.acknowledge();
    } catch (BusinessException e) {
      // PG 거절(결정적)만 보상한다. 통신 실패는 환불 성공 여부가 불명이라 재시도→DLT로 넘겨 섣부른 REFUND_FAILED 확정을 피한다.
      if (event != null && e.getErrorStatus() == ErrorStatus.PAYMENT_REFUND_FAILED) {
        log.error(
            "[CRITICAL] PG 환불 거절(결정적) → 보상 이벤트(RefundFailed) 발행. bookingId: {}",
            event.bookingId(),
            e);
        paymentEventPublisher.publishRefundFailed(
            event.bookingId(), event.bookingNumber(), REASON_REFUND_FAILED, LocalDateTime.now());
        Counter.builder(MetricNames.PAYMENT_REFUND_FAILED).register(meterRegistry).increment();
        ack.acknowledge();
      } else {
        log.warn(
            "환불 요청 처리 중 재시도 가능한 실패(통신 오류 등). 재시도합니다. bookingId: {}",
            event != null ? event.bookingId() : null,
            e);
        throw e;
      }
    } catch (Exception e) {
      // 역직렬화 실패 등 대상 식별 불가 또는 일시(인프라) 실패 → 재시도→DLT로 메시지를 보존한다(#269).
      log.warn(
          "환불 요청 처리 중 일시적 오류. 재시도합니다. eventId: {}, bookingId: {}",
          envelope.eventId(),
          event != null ? event.bookingId() : null,
          e);
      throw e;
    }
  }
}
