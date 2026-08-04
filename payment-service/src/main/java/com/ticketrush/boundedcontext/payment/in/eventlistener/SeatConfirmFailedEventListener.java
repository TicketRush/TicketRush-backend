package com.ticketrush.boundedcontext.payment.in.eventlistener;

import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.app.usecase.FailedRefundRecorder;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentRefundByBookingUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentRefundByBookingUseCase.RefundOutcome;
import com.ticketrush.boundedcontext.payment.domain.types.RefundTrigger;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.booking.event.SeatConfirmFailedEvent;
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
 * 좌석 확정 실패({@link SeatConfirmFailedEvent}, #489)를 수신해 과금을 자동 환불로 되돌린다 (#492).
 *
 * <p>되돌리는 대상은 "과금은 됐는데 좌석이 없는" 건이다. 대량 만료 구간에서 만료 이벤트 전파가 밀리면 이미 만료된 예매의 결제가 통과하고(#490 이 좁혔으나 없애지는
 * 못한 잔여 창), 그 뒤 좌석 확정이 실패하면 payment 는 COMPLETED·booking 은 CONFIRMED·좌석은 남의 것인 상태로 끝난다. 이 리스너가 그 마지막
 * 방어선이다.
 *
 * <p>환불이 성공하면 나머지는 기존 배선이 자동으로 따라온다 — {@code PaymentCanceledEvent} 를 booking(REFUNDED 종결)·seat(좌석
 * 반환)·ticket(입장권 취소)이 각각 소비한다. 좌석은 이미 남의 것이지만 {@code bookingNumber} 를 실어 보내므로 seat 의 소유 교차검증(ABA
 * 방지)이 타 예매의 좌석을 건드리지 않는다.
 *
 * <p><b>티켓 사용 여부를 보지 않는다.</b> 결제 취소 API({@code PaymentCancelUseCase})는 USED 면 환불을 막지만(#416), 그 가드는
 * <i>사용자가 요청한 취소</i>의 계약이다. 이 경로는 시스템이 자기 사고를 되돌리는 것이라 계약이 다르다. 가드를 걸면 USED 건은 환불이 시도조차 되지 않는데, 관리자
 * 재환불 API 역시 입장한 예매를 거부하므로({@code BookingValidateTicketNotUsedUseCase#executeForAdmin}) 남는 복구 수단이
 * 수동 DB 조작뿐이 된다. 가드가 없으면 최소한 자동 환불이 시도되고 대개 성공한다.
 *
 * <p>좌석 재판매 위험은 여기엔 없다 — 이 예매의 좌석이 아니라 {@code bookingNumber} ABA 교차검증이 막는다. ADR 0005 가 트레이드오프로 적어둔
 * "실제로 착석한 좌석이 재판매 가능해진다"는 사용자 취소 경로의 이야기다.
 *
 * <p><b>Inbox(#110) 미사용 근거</b>: {@code InboxService.runIfFirst} 는 {@code @Transactional} 로 트랜잭션을 열고
 * 콜백을 그 안에서 실행하는데, PG 취소는 외부 왕복이라 트랜잭션에 들일 수 없다({@code RefundRequestedEventListener} 와 같은 이유). 멱등은
 * 결제 상태 선검사(COMPLETED 만 환불) + {@code Refund.paymentId} unique(#296) + PG 고정 멱등 키({@code
 * REFUND-%07d})로 보장한다. 발행 측은 중복 억제를 하지 않으므로(#489) 멱등 책임은 전적으로 이쪽이다.
 *
 * <p><b>#269 표준 분류를 그대로 쓰지 않는 근거</b>: PG 통신 실패도 {@code BusinessException} 이라 {@code
 * KafkaConsumerErrorPolicy.isPermanent} 로는 영구로 분류돼 ack 된다. 그러면 <b>과금된 건의 보상 신호가 조용히 유실된다</b>. PG
 * 거절({@code PAYMENT_REFUND_FAILED})만 {@code ErrorStatus} 로 직접 식별해 보상하고 나머지는 재시도→DLT 로 넘긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatConfirmFailedEventListener {

  private static final String REASON_REFUND_FAILED = "좌석 확정 실패 보상 환불의 PG 거절";

  private final PaymentRefundByBookingUseCase paymentRefundByBookingUseCase;
  private final PaymentEventPublisher paymentEventPublisher;
  private final JsonConverter jsonConverter;
  private final MeterRegistry meterRegistry;

  @KafkaListener(topics = SeatConfirmFailedEvent.TOPIC, groupId = KafkaConsumerGroup.PAYMENT)
  public void handleSeatConfirmFailed(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {
    SeatConfirmFailedEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), SeatConfirmFailedEvent.class);

      RefundOutcome outcome =
          paymentRefundByBookingUseCase.execute(
              event.bookingId(), event.bookingNumber(), RefundTrigger.SEAT_CONFIRM_FAILED);

      logOutcome(outcome, envelope, event);

      Counter.builder(MetricNames.PAYMENT_REFUND)
          .tag(MetricNames.TAG_OUTCOME, outcome.name().toLowerCase())
          .tag(MetricNames.TAG_TRIGGER, RefundTrigger.SEAT_CONFIRM_FAILED.tag())
          .register(meterRegistry)
          .increment();

      // 성공(재발행·멱등 스킵 포함) 시에만 오프셋을 커밋한다(#269 표준).
      ack.acknowledge();
    } catch (DataIntegrityViolationException e) {
      // 동시 환불이 unique 제약(#296)에 막힌 경우 = 이미 다른 트랜잭션에서 보상이 확정됨. 멱등 정상으로 간주하고 커밋한다.
      log.info(
          "동시 중복 보상 환불(unique 경합). eventId: {}, bookingId: {}",
          envelope.eventId(),
          event != null ? event.bookingId() : null);
      ack.acknowledge();
    } catch (BusinessException e) {
      handleBusinessFailure(e, envelope, event, ack);
    } catch (Exception e) {
      // 일시(인프라) 실패 → 재시도→DLT 로 보존한다(#269). 역직렬화 실패는 DeserializationException 이
      // BusinessException 하위라 위 분기가 먼저 잡지만, 거기서도 결정적 거절이 아니라 rethrow 된다.
      // 이 토픽의 DLT 는 곧 "환불되지 않은 과금 건"이라 다른 DLT 보다 운영 우선순위가 높다.
      log.warn(
          "좌석 확정 실패 보상 처리 중 일시적 오류. 재시도합니다. eventId: {}, bookingId: {}",
          envelope.eventId(),
          event != null ? event.bookingId() : null,
          e);
      throw e;
    }
  }

  /**
   * 좌석 확정 실패 <b>자체</b>는 booking 이 이미 CRITICAL 로 남긴다({@code PaymentConfirmedEventListener}). 한 사건에
   * 알림이 두 번 울지 않도록 여기서는 보상의 결과만 남기고, 새로운 사건일 때만 CRITICAL 로 올린다.
   */
  private void logOutcome(
      RefundOutcome outcome, DomainEventEnvelope envelope, SeatConfirmFailedEvent event) {
    switch (outcome) {
      case REFUNDED ->
          log.info(
              "좌석 확정 실패 보상 환불 완료. eventId: {}, bookingId: {}, seatId: {}",
              envelope.eventId(),
              event.bookingId(),
              event.seatId());
      case REPUBLISHED ->
          log.info(
              "이미 환불된 건이라 PaymentCanceledEvent 를 재발행(self-heal). eventId: {}, bookingId: {}",
              envelope.eventId(),
              event.bookingId());
      // 이 신호의 전제는 "과금됐다"이다. 결제가 없다면 그 전제가 깨진 것이고, 추적한 어떤 경로로도 도달하지 않는다.
      // 취소 요청 경로(#91)에서 같은 값이 "이미 정리된 건"을 뜻하는 것과 달리 여기서는 정합성 붕괴 신호다.
      case ALREADY_SETTLED ->
          log.error(
              "[CRITICAL] 보상 대상 결제가 없습니다! 신호의 전제(과금)가 깨졌습니다. 확인이 필요합니다. eventId: {}, bookingId: {}",
              envelope.eventId(),
              event.bookingId());
      default -> throw new IllegalStateException("처리되지 않은 RefundOutcome: " + outcome);
    }
  }

  /**
   * PG 거절(결정적)만 보상 이벤트로 확정하고, 통신 실패는 환불 성공 여부가 불명이라 재시도에 위임한다.
   *
   * <p>결정적 거절 판별은 FAILED 이력 기록({@code FailedRefundRecorder})과 같은 술어를 공유해 저장/보상 짝이 어긋나지 않게 한다(#334).
   */
  private void handleBusinessFailure(
      BusinessException e,
      DomainEventEnvelope envelope,
      SeatConfirmFailedEvent event,
      Acknowledgment ack) {
    if (event == null || !FailedRefundRecorder.isDeterministicRejection(e)) {
      log.warn(
          "좌석 확정 실패 보상 중 재시도 가능한 실패(통신 오류 등). 재시도합니다. eventId: {}, bookingId: {}",
          envelope.eventId(),
          event != null ? event.bookingId() : null,
          e);
      throw e;
    }

    // 과금된 돈이 돌아가지 않은 채 남는다. 자동 복구가 끝난 지점이므로 수동 개입 대상으로 올린다.
    log.error(
        "[CRITICAL] 좌석 확정 실패 보상 환불을 PG 가 거절했습니다! 과금이 남아 수동 처리가 필요합니다. eventId: {}, bookingId: {}",
        envelope.eventId(),
        event.bookingId(),
        e);

    // booking 의 refundFailedAt 을 채워 관리자 재환불 API 와 미해결 목록에 이 건이 잡히게 한다(#391).
    paymentEventPublisher.publishRefundFailed(
        event.bookingId(), event.bookingNumber(), REASON_REFUND_FAILED, LocalDateTime.now());

    Counter.builder(MetricNames.PAYMENT_REFUND_FAILED)
        .tag(MetricNames.TAG_TRIGGER, RefundTrigger.SEAT_CONFIRM_FAILED.tag())
        .register(meterRegistry)
        .increment();

    // 재시도가 무의미한 확정 실패라 오프셋을 커밋한다. 복구는 RefundFailedEvent 와 CRITICAL 로 넘어갔다.
    ack.acknowledge();
  }
}
