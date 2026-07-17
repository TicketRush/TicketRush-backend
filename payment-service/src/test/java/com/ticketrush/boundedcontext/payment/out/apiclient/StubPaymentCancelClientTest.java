package com.ticketrush.boundedcontext.payment.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubPaymentCancelClientTest {

  private final StubPaymentCancelClient client = new StubPaymentCancelClient();

  @Test
  @DisplayName("fallback 클라이언트로 표시된다")
  void is_fallback() {
    assertThat(client.isFallback()).isTrue();
  }

  @Test
  @DisplayName("fallback 이므로 단일 provider 매핑 조회 시 예외를 던진다")
  void provider_throws() {
    assertThatThrownBy(client::provider).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("요청 금액 그대로 취소 응답을 만든다")
  void cancel_returns_result_with_request_amount() {
    PaymentCancelResult result =
        client.cancel(
            new PaymentCancelCommand(
                PaymentProvider.TOSS, "pgKey_xyz", 1_000L, "사유", "REFUND-0000001"));

    assertThat(result.refundedAmount()).isEqualTo(1_000L);
    assertThat(result.pgRefundKey()).isNotBlank();
    assertThat(result.canceledAt()).isNotNull();
  }
}
