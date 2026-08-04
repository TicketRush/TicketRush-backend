package com.ticketrush.boundedcontext.payment.in.eventlistener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentRefundByBookingUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentRefundByBookingUseCase.RefundOutcome;
import com.ticketrush.boundedcontext.payment.domain.types.RefundTrigger;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.SeatConfirmFailedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;

/**
 * 좌석 확정 실패 보상(#492) 리스너 단위 테스트.
 *
 * <p>종단 경로는 booking-service 가 검증한다 — 만료된 예매로 결제가 통과하고(#490 잔여 창) 좌석 확정이 {@code SEAT_409_003} 으로
 * 실패하면 {@code PaymentConfirmedEventListener} 가 {@code BookingPublishSeatConfirmFailedUseCase} 로 신호를
 * 발행한다(#489). payment 는 그 신호만 받으며 페이로드에 실패 원인이 없어 "만료 예매였는지"를 알지 못한다. 따라서 여기서는 신호 수신 이후만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SeatConfirmFailedEventListenerTest {

  private SeatConfirmFailedEventListener listener;

  @Mock private PaymentRefundByBookingUseCase paymentRefundByBookingUseCase;
  @Mock private PaymentEventPublisher paymentEventPublisher;
  @Mock private JsonConverter jsonConverter;
  @Mock private Acknowledgment acknowledgment;

  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    listener =
        new SeatConfirmFailedEventListener(
            paymentRefundByBookingUseCase, paymentEventPublisher, jsonConverter, meterRegistry);
  }

  private static final String PAYLOAD = "payload";
  private static final Long BOOKING_ID = 100L;
  private static final String BOOKING_NUMBER = "BOOK-1234";
  private static final Long SEAT_ID = 200L;

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        "event-id",
        SeatConfirmFailedEvent.EVENT_NAME,
        Instant.now(),
        SeatConfirmFailedEvent.TOPIC,
        PAYLOAD,
        null);
  }

  private SeatConfirmFailedEvent event() {
    return new SeatConfirmFailedEvent(
        BOOKING_ID, BOOKING_NUMBER, SEAT_ID, 10L, LocalDateTime.of(2026, 5, 22, 10, 0));
  }

  private void givenOutcome(RefundOutcome outcome) {
    given(jsonConverter.deserialize(PAYLOAD, SeatConfirmFailedEvent.class)).willReturn(event());
    given(
            paymentRefundByBookingUseCase.execute(
                BOOKING_ID, BOOKING_NUMBER, RefundTrigger.SEAT_CONFIRM_FAILED))
        .willReturn(outcome);
  }

  private void givenFailure(Throwable failure) {
    given(jsonConverter.deserialize(PAYLOAD, SeatConfirmFailedEvent.class)).willReturn(event());
    willThrow(failure)
        .given(paymentRefundByBookingUseCase)
        .execute(BOOKING_ID, BOOKING_NUMBER, RefundTrigger.SEAT_CONFIRM_FAILED);
  }

  @Test
  @DisplayName("만료된 예매로 결제가 통과한 뒤 좌석 확정이 실패하면 그 결제를 자동 환불하고 오프셋을 커밋한다")
  void handleSeatConfirmFailed_refunds() {
    // given
    givenOutcome(RefundOutcome.REFUNDED);

    // when
    listener.handleSeatConfirmFailed(envelope(), acknowledgment);

    // then
    verify(paymentEventPublisher, never()).publishRefundFailed(any(), any(), any(), any());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("보상 환불은 사용자 취소와 구분되도록 SEAT_CONFIRM_FAILED 트리거로 실행된다")
  void handleSeatConfirmFailed_usesCompensationTrigger() {
    // given: 트리거가 PG 취소 사유·Refund.reason·메트릭 태그를 함께 가른다
    givenOutcome(RefundOutcome.REFUNDED);

    // when
    listener.handleSeatConfirmFailed(envelope(), acknowledgment);

    // then
    verify(paymentRefundByBookingUseCase)
        .execute(BOOKING_ID, BOOKING_NUMBER, RefundTrigger.SEAT_CONFIRM_FAILED);

    assertThat(
            meterRegistry
                .counter(
                    MetricNames.PAYMENT_REFUND,
                    MetricNames.TAG_OUTCOME,
                    "refunded",
                    MetricNames.TAG_TRIGGER,
                    RefundTrigger.SEAT_CONFIRM_FAILED.tag())
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("신호가 중복 수신되어 이미 환불된 건이면 재발행(REPUBLISHED)으로 흡수하고 보상 없이 커밋한다")
  void handleSeatConfirmFailed_republished() {
    // given: 릴레이는 at-least-once 라 같은 신호가 여러 번 도착한다(#344 실측 3.09배).
    // 두 번째부터는 COMPLETED 결제가 없어 PG 재호출 없이 커밋된 환불 데이터만 재전파된다.
    givenOutcome(RefundOutcome.REPUBLISHED);

    // when
    listener.handleSeatConfirmFailed(envelope(), acknowledgment);

    // then
    verify(paymentEventPublisher, never()).publishRefundFailed(any(), any(), any(), any());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("보상 대상 결제가 없으면(ALREADY_SETTLED) 전제 붕괴로 보고 보상 없이 커밋한다")
  void handleSeatConfirmFailed_alreadySettled() {
    // given: 이 신호의 전제는 "과금됐다"이므로, 취소 요청 경로와 달리 정상 상황이 아니다(CRITICAL 로그 대상).
    givenOutcome(RefundOutcome.ALREADY_SETTLED);

    // when
    listener.handleSeatConfirmFailed(envelope(), acknowledgment);

    // then
    verify(paymentEventPublisher, never()).publishRefundFailed(any(), any(), any(), any());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("동시 보상 unique 경합(DataIntegrityViolationException)이면 보상 없이 멱등 커밋한다")
  void handleSeatConfirmFailed_concurrentRace() {
    // given
    givenFailure(new DataIntegrityViolationException("duplicate refund"));

    // when & then
    assertThatCode(() -> listener.handleSeatConfirmFailed(envelope(), acknowledgment))
        .doesNotThrowAnyException();

    verify(paymentEventPublisher, never()).publishRefundFailed(any(), any(), any(), any());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("PG 거절(결정적)이면 RefundFailedEvent 를 발행해 관리자 복구 경로를 열고 커밋한다")
  void handleSeatConfirmFailed_pgRejected_publishesCompensation() {
    // given: 과금이 남은 채 자동 복구가 끝난 지점이다. booking 의 refundFailedAt 이 채워져야
    // 관리자 재환불 API(BOOKING_REFUND_RETRY_NOT_ALLOWED 가드)와 미해결 목록에 이 건이 잡힌다(#391).
    givenFailure(new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED));

    // when & then
    assertThatCode(() -> listener.handleSeatConfirmFailed(envelope(), acknowledgment))
        .doesNotThrowAnyException();

    verify(paymentEventPublisher)
        .publishRefundFailed(
            eq(BOOKING_ID), eq(BOOKING_NUMBER), any(String.class), any(LocalDateTime.class));
    verify(acknowledgment).acknowledge();

    assertThat(
            meterRegistry
                .counter(
                    MetricNames.PAYMENT_REFUND_FAILED,
                    MetricNames.TAG_TRIGGER,
                    RefundTrigger.SEAT_CONFIRM_FAILED.tag())
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("PG 통신 실패면 보상 없이 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT)")
  void handleSeatConfirmFailed_pgCommunicationFailure_rethrows() {
    // given: 이 케이스를 #269 표준(KafkaConsumerErrorPolicy.isPermanent)으로 분류하면 BusinessException 이라
    // 영구로 판정돼 ack 된다 — 과금된 건의 보상 신호가 조용히 유실된다. 그 회귀를 막는 테스트다.
    givenFailure(new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED));

    // when & then
    assertThatThrownBy(() -> listener.handleSeatConfirmFailed(envelope(), acknowledgment))
        .isInstanceOf(BusinessException.class);

    verify(paymentEventPublisher, never()).publishRefundFailed(any(), any(), any(), any());
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("일시적(인프라) 실패면 보상 없이 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT)")
  void handleSeatConfirmFailed_transientFailure_rethrows() {
    // given
    givenFailure(new RuntimeException("일시 장애"));

    // when & then
    assertThatThrownBy(() -> listener.handleSeatConfirmFailed(envelope(), acknowledgment))
        .isInstanceOf(RuntimeException.class);

    verify(paymentEventPublisher, never()).publishRefundFailed(any(), any(), any(), any());
    verify(acknowledgment, never()).acknowledge();
  }
}
