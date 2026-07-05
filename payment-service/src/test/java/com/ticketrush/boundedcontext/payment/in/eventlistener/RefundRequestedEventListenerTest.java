package com.ticketrush.boundedcontext.payment.in.eventlistener;

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
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.RefundRequestedEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class RefundRequestedEventListenerTest {

  @InjectMocks private RefundRequestedEventListener listener;

  @Mock private PaymentRefundByBookingUseCase paymentRefundByBookingUseCase;
  @Mock private PaymentEventPublisher paymentEventPublisher;
  @Mock private JsonConverter jsonConverter;
  @Mock private Acknowledgment acknowledgment;

  private static final String PAYLOAD = "payload";
  private static final Long BOOKING_ID = 100L;
  private static final String BOOKING_NUMBER = "BOOK-1234";

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        "event-id",
        RefundRequestedEvent.EVENT_NAME,
        Instant.now(),
        RefundRequestedEvent.TOPIC,
        PAYLOAD,
        null);
  }

  private RefundRequestedEvent event() {
    return new RefundRequestedEvent(
        BOOKING_ID, BOOKING_NUMBER, 200L, 10L, LocalDateTime.of(2026, 5, 22, 10, 0));
  }

  @Test
  @DisplayName("환불 성공(REFUNDED)이면 보상 이벤트 없이 오프셋을 커밋한다")
  void handleRefundRequested_success() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, RefundRequestedEvent.class)).willReturn(event());
    given(paymentRefundByBookingUseCase.execute(any(RefundRequestedEvent.class)))
        .willReturn(RefundOutcome.REFUNDED);

    // when
    listener.handleRefundRequested(envelope(), acknowledgment);

    // then
    verify(paymentEventPublisher, never()).publishRefundFailed(any(), any(), any(), any());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이미 환불됨(ALREADY_SETTLED)이면 멱등 스킵하고 오프셋을 커밋한다")
  void handleRefundRequested_alreadySettled() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, RefundRequestedEvent.class)).willReturn(event());
    given(paymentRefundByBookingUseCase.execute(any(RefundRequestedEvent.class)))
        .willReturn(RefundOutcome.ALREADY_SETTLED);

    // when
    listener.handleRefundRequested(envelope(), acknowledgment);

    // then
    verify(paymentEventPublisher, never()).publishRefundFailed(any(), any(), any(), any());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이미 환불된 건 재발행(REPUBLISHED)이면 보상 없이 오프셋을 커밋한다")
  void handleRefundRequested_republished() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, RefundRequestedEvent.class)).willReturn(event());
    given(paymentRefundByBookingUseCase.execute(any(RefundRequestedEvent.class)))
        .willReturn(RefundOutcome.REPUBLISHED);

    // when
    listener.handleRefundRequested(envelope(), acknowledgment);

    // then
    verify(paymentEventPublisher, never()).publishRefundFailed(any(), any(), any(), any());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("동시 환불 unique 경합(DataIntegrityViolationException)이면 보상 없이 멱등 커밋한다")
  void handleRefundRequested_concurrentRefundRace() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, RefundRequestedEvent.class)).willReturn(event());
    willThrow(new DataIntegrityViolationException("duplicate refund"))
        .given(paymentRefundByBookingUseCase)
        .execute(any(RefundRequestedEvent.class));

    // when & then
    assertThatCode(() -> listener.handleRefundRequested(envelope(), acknowledgment))
        .doesNotThrowAnyException();

    verify(paymentEventPublisher, never()).publishRefundFailed(any(), any(), any(), any());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("PG 거절(PAYMENT_REFUND_FAILED, 결정적)이면 RefundFailedEvent를 발행하고 오프셋을 커밋한다(재시도 안 함)")
  void handleRefundRequested_pgRejected_publishesCompensation() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, RefundRequestedEvent.class)).willReturn(event());
    willThrow(new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED))
        .given(paymentRefundByBookingUseCase)
        .execute(any(RefundRequestedEvent.class));

    // when & then
    assertThatCode(() -> listener.handleRefundRequested(envelope(), acknowledgment))
        .doesNotThrowAnyException();

    verify(paymentEventPublisher)
        .publishRefundFailed(
            eq(BOOKING_ID), eq(BOOKING_NUMBER), eq("PG 환불 처리 실패"), any(LocalDateTime.class));
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("PG 통신 실패(PAYMENT_PG_COMMUNICATION_FAILED, 일시적)면 보상 없이 예외를 전파한다(재시도→DLT)")
  void handleRefundRequested_pgCommunicationFailure_rethrowsWithoutCompensation() {
    // given: 환불 성공 여부가 불명이라 섣불리 REFUND_FAILED로 확정하지 않고 재시도에 위임한다
    given(jsonConverter.deserialize(PAYLOAD, RefundRequestedEvent.class)).willReturn(event());
    willThrow(new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED))
        .given(paymentRefundByBookingUseCase)
        .execute(any(RefundRequestedEvent.class));

    // when & then
    assertThatThrownBy(() -> listener.handleRefundRequested(envelope(), acknowledgment))
        .isInstanceOf(BusinessException.class);

    verify(paymentEventPublisher, never()).publishRefundFailed(any(), any(), any(), any());
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("일시적(인프라) 실패면 보상 없이 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT)")
  void handleRefundRequested_transientFailure_rethrows() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, RefundRequestedEvent.class)).willReturn(event());
    willThrow(new RuntimeException("PG 일시 장애"))
        .given(paymentRefundByBookingUseCase)
        .execute(any(RefundRequestedEvent.class));

    // when & then
    assertThatThrownBy(() -> listener.handleRefundRequested(envelope(), acknowledgment))
        .isInstanceOf(RuntimeException.class);

    verify(paymentEventPublisher, never()).publishRefundFailed(any(), any(), any(), any());
    verify(acknowledgment, never()).acknowledge();
  }
}
