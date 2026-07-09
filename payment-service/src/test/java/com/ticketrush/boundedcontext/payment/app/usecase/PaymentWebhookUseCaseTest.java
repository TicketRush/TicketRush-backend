package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentInquiryClientRouter;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentInquiryResult;
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

  private static final String PAYMENT_KEY = "tossKey_abc";
  private static final byte[] BODY =
      """
      {
        "eventType": "PAYMENT_STATUS_CHANGED",
        "data": { "paymentKey": "tossKey_abc", "orderId": "BKG-0000100", "status": "DONE" }
      }
      """
          .getBytes(StandardCharsets.UTF_8);

  @Mock private PaymentInquiryClientRouter inquiryClientRouter;
  @Mock private PaymentRepository paymentRepository;

  private PaymentWebhookUseCase paymentWebhookUseCase;

  @BeforeEach
  void setUp() {
    paymentWebhookUseCase = new PaymentWebhookUseCase(inquiryClientRouter, paymentRepository);
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

  private void givenPgHasPayment() {
    given(inquiryClientRouter.inquire(PaymentProvider.TOSS, PAYMENT_KEY))
        .willReturn(
            Optional.of(
                new PaymentInquiryResult(
                    PAYMENT_KEY, "BKG-0000100", 55_000L, "DONE", LocalDateTime.now())));
  }

  /** 형식상 유효한 paymentKey 전용 헬퍼. 인자를 JSON 이스케이프하지 않으므로 {@code "}·{@code \} 등이 든 키에는 쓰지 않는다. */
  private byte[] bodyWithKey(String paymentKey) {
    String json =
        "{ \"eventType\": \"PAYMENT_STATUS_CHANGED\", \"data\": { \"paymentKey\": \""
            + paymentKey
            + "\", \"orderId\": \"BKG-0000100\", \"status\": \"DONE\" } }";
    return json.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("재조회로 존재가 확인되고 COMPLETED 결제가 있으면 멱등 처리하고 예외 없이 끝난다")
  void handle_idempotent_when_payment_completed() {
    // given
    givenPgHasPayment();
    given(paymentRepository.findByPaymentKey(PAYMENT_KEY))
        .willReturn(Optional.of(payment(PaymentStatus.COMPLETED)));

    // when & then
    assertThatCode(() -> paymentWebhookUseCase.handle(BODY)).doesNotThrowAnyException();
    verify(inquiryClientRouter).inquire(PaymentProvider.TOSS, PAYMENT_KEY);
    verify(paymentRepository).findByPaymentKey(PAYMENT_KEY);
  }

  @Test
  @DisplayName("재조회는 성공했으나 로컬 Payment가 없으면 예외 없이 끝난다(CRITICAL 로그로만 추적)")
  void handle_logs_and_returns_when_payment_missing() {
    // given
    givenPgHasPayment();
    given(paymentRepository.findByPaymentKey(PAYMENT_KEY)).willReturn(Optional.empty());

    // when & then
    assertThatCode(() -> paymentWebhookUseCase.handle(BODY)).doesNotThrowAnyException();
    verify(paymentRepository).findByPaymentKey(PAYMENT_KEY);
  }

  @Test
  @DisplayName("PG에 존재하지 않는 paymentKey(위조)면 PAYMENT_401_001로 거부하고 로컬 조회하지 않는다")
  void handle_rejects_when_payment_not_found_at_pg() {
    // given
    given(inquiryClientRouter.inquire(PaymentProvider.TOSS, PAYMENT_KEY))
        .willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> paymentWebhookUseCase.handle(BODY))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_WEBHOOK_INVALID);
    verify(paymentRepository, never()).findByPaymentKey(any());
  }

  @Test
  @DisplayName("재조회 통신 실패는 예외가 그대로 전파되고 로컬 조회하지 않는다(재전송 유도)")
  void handle_propagates_when_inquiry_communication_fails() {
    // given
    willThrow(new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED))
        .given(inquiryClientRouter)
        .inquire(PaymentProvider.TOSS, PAYMENT_KEY);

    // when & then
    assertThatThrownBy(() -> paymentWebhookUseCase.handle(BODY))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
    verify(paymentRepository, never()).findByPaymentKey(any());
  }

  @Test
  @DisplayName("paymentKey가 없는 이벤트는 재조회 없이 무시한다(처리 대상 아님)")
  void handle_ignores_when_payment_key_missing() {
    // given
    byte[] bodyWithoutKey =
        "{ \"eventType\": \"PAYMENT_STATUS_CHANGED\", \"data\": { \"orderId\": \"BKG-0000100\" } }"
            .getBytes(StandardCharsets.UTF_8);

    // when & then
    assertThatCode(() -> paymentWebhookUseCase.handle(bodyWithoutKey)).doesNotThrowAnyException();
    verify(inquiryClientRouter, never()).inquire(any(), any());
    verify(paymentRepository, never()).findByPaymentKey(any());
  }

  @Test
  @DisplayName("paymentKey가 200자를 초과하면 재조회 없이 PAYMENT_401_001로 거부한다(증폭 방어)")
  void handle_rejects_when_payment_key_too_long() {
    // given
    byte[] tooLong = bodyWithKey("a".repeat(201));

    // when & then
    assertThatThrownBy(() -> paymentWebhookUseCase.handle(tooLong))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_WEBHOOK_INVALID);
    verify(inquiryClientRouter, never()).inquire(any(), any());
    verify(paymentRepository, never()).findByPaymentKey(any());
  }

  @Test
  @DisplayName("paymentKey에 제어문자가 섞이면 재조회 없이 PAYMENT_401_001로 거부한다(증폭 방어)")
  void handle_rejects_when_payment_key_has_control_char() {
    // given (JSON 이스케이프 \n → 파싱 시 실제 개행 0x0A, 인쇄가능 ASCII 범위 밖)
    byte[] withControlChar =
        "{ \"eventType\": \"PAYMENT_STATUS_CHANGED\", \"data\": { \"paymentKey\": \"toss\\nkey\", \"orderId\": \"BKG-0000100\", \"status\": \"DONE\" } }"
            .getBytes(StandardCharsets.UTF_8);

    // when & then
    assertThatThrownBy(() -> paymentWebhookUseCase.handle(withControlChar))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_WEBHOOK_INVALID);
    verify(inquiryClientRouter, never()).inquire(any(), any());
    verify(paymentRepository, never()).findByPaymentKey(any());
  }

  @Test
  @DisplayName("정확히 200자인 정상 형식 paymentKey는 사전 필터를 통과해 재조회까지 도달한다(오탐 없음)")
  void handle_passes_filter_when_payment_key_at_boundary() {
    // given
    String key200 = "a".repeat(200);
    given(inquiryClientRouter.inquire(PaymentProvider.TOSS, key200))
        .willReturn(
            Optional.of(
                new PaymentInquiryResult(
                    key200, "BKG-0000100", 55_000L, "DONE", LocalDateTime.now())));
    given(paymentRepository.findByPaymentKey(key200)).willReturn(Optional.empty());

    // when & then
    assertThatCode(() -> paymentWebhookUseCase.handle(bodyWithKey(key200)))
        .doesNotThrowAnyException();
    verify(inquiryClientRouter).inquire(PaymentProvider.TOSS, key200);
  }

  @Test
  @DisplayName("페이로드 JSON이 깨졌으면 재조회 없이 PAYMENT_400_006으로 실패한다")
  void handle_fails_when_payload_malformed() {
    // given
    byte[] malformed = "{ not-a-json".getBytes(StandardCharsets.UTF_8);

    // when & then
    assertThatThrownBy(() -> paymentWebhookUseCase.handle(malformed))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_WEBHOOK_PAYLOAD_INVALID);
    verify(inquiryClientRouter, never()).inquire(any(), any());
  }

  @Test
  @DisplayName("재조회 성공 후 로컬 Payment가 COMPLETED가 아니어도 예외 없이 끝난다")
  void handle_returns_when_payment_not_completed() {
    // given
    givenPgHasPayment();
    given(paymentRepository.findByPaymentKey(PAYMENT_KEY))
        .willReturn(Optional.of(payment(PaymentStatus.PENDING)));

    // when & then
    assertThatCode(() -> paymentWebhookUseCase.handle(BODY)).doesNotThrowAnyException();
    verify(inquiryClientRouter).inquire(PaymentProvider.TOSS, PAYMENT_KEY);
  }
}
