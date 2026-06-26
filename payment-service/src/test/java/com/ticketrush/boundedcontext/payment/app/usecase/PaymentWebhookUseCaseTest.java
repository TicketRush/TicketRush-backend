package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.app.support.PaymentWebhookVerifier;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookUseCaseTest {

  private static final String SIGNATURE = "valid-signature";
  private static final String PAYMENT_KEY = "tossKey_abc";
  private static final byte[] BODY =
      """
      {
        "eventType": "PAYMENT_STATUS_CHANGED",
        "data": { "paymentKey": "tossKey_abc", "orderId": "BKG-0000100", "status": "DONE" }
      }
      """
          .getBytes(StandardCharsets.UTF_8);

  @Mock private PaymentWebhookVerifier webhookVerifier;
  @Mock private PaymentRepository paymentRepository;

  private PaymentWebhookUseCase paymentWebhookUseCase;

  @BeforeEach
  void setUp() {
    paymentWebhookUseCase = new PaymentWebhookUseCase(webhookVerifier, paymentRepository);
  }

  private Payment payment(PaymentStatus status) {
    return Payment.builder()
        .bookingId(100L)
        .userId(10L)
        .seatId(200L)
        .provider(PaymentProvider.TOSS)
        .amount(55_000L)
        .status(status)
        .paymentKey(PAYMENT_KEY)
        .approvalNumber("APR-1")
        .paidAt(LocalDateTime.of(2026, 5, 22, 10, 0))
        .build();
  }

  @Test
  @DisplayName("기존 COMPLETED 결제가 있으면 멱등 처리하고 예외 없이 끝난다")
  void handle_idempotent_when_payment_completed() {
    // given
    given(paymentRepository.findByPaymentKey(PAYMENT_KEY))
        .willReturn(Optional.of(payment(PaymentStatus.COMPLETED)));

    // when & then
    assertThatCode(() -> paymentWebhookUseCase.handle(BODY, SIGNATURE)).doesNotThrowAnyException();
    verify(webhookVerifier).verify(any(byte[].class), eq(SIGNATURE));
    verify(paymentRepository).findByPaymentKey(PAYMENT_KEY);
  }

  @Test
  @DisplayName("Payment가 없으면(누락) 예외 없이 끝난다(CRITICAL 로그로만 추적)")
  void handle_logs_and_returns_when_payment_missing() {
    // given
    given(paymentRepository.findByPaymentKey(PAYMENT_KEY)).willReturn(Optional.empty());

    // when & then
    assertThatCode(() -> paymentWebhookUseCase.handle(BODY, SIGNATURE)).doesNotThrowAnyException();
    verify(paymentRepository).findByPaymentKey(PAYMENT_KEY);
  }

  @Test
  @DisplayName("서명 검증에 실패하면 예외가 전파되고 조회하지 않는다")
  void handle_propagates_when_signature_invalid() {
    // given
    willThrow(new BusinessException(ErrorStatus.PAYMENT_WEBHOOK_SIGNATURE_INVALID))
        .given(webhookVerifier)
        .verify(any(byte[].class), any());

    // when & then
    assertThatThrownBy(() -> paymentWebhookUseCase.handle(BODY, SIGNATURE))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_WEBHOOK_SIGNATURE_INVALID);
    verify(paymentRepository, never()).findByPaymentKey(any());
  }

  @Test
  @DisplayName("paymentKey가 없는 이벤트는 예외 없이 무시한다(처리 대상 아님)")
  void handle_ignores_when_payment_key_missing() {
    // given
    byte[] bodyWithoutKey =
        "{ \"eventType\": \"PAYMENT_STATUS_CHANGED\", \"data\": { \"orderId\": \"BKG-0000100\" } }"
            .getBytes(StandardCharsets.UTF_8);

    // when & then
    assertThatCode(() -> paymentWebhookUseCase.handle(bodyWithoutKey, SIGNATURE))
        .doesNotThrowAnyException();
    verify(paymentRepository, never()).findByPaymentKey(any());
  }

  @Test
  @DisplayName("페이로드 JSON이 깨졌으면 PAYMENT_400_006으로 실패한다")
  void handle_fails_when_payload_malformed() {
    // given
    byte[] malformed = "{ not-a-json".getBytes(StandardCharsets.UTF_8);

    // when & then
    assertThatThrownBy(() -> paymentWebhookUseCase.handle(malformed, SIGNATURE))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_WEBHOOK_PAYLOAD_INVALID);
  }

  @Test
  @DisplayName("Payment가 존재하나 COMPLETED가 아니어도 예외 없이 끝난다")
  void handle_returns_when_payment_not_completed() {
    // given
    given(paymentRepository.findByPaymentKey(PAYMENT_KEY))
        .willReturn(Optional.of(payment(PaymentStatus.PENDING)));

    // when & then
    assertThatCode(() -> paymentWebhookUseCase.handle(BODY, SIGNATURE)).doesNotThrowAnyException();
    verify(webhookVerifier).verify(any(byte[].class), eq(SIGNATURE));
  }
}
