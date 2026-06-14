package com.ticketrush.boundedcontext.payment.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubPaymentCancelClientTest {

  private final StubPaymentCancelClient client = new StubPaymentCancelClient();

  @Test
  @DisplayName("모든 provider를 지원한다 (fallback 클라이언트)")
  void supports_all_providers() {
    assertThat(client.supports(PaymentProvider.TOSS)).isTrue();
    assertThat(client.supports(PaymentProvider.KAKAO)).isTrue();
    assertThat(client.supports(PaymentProvider.NAVER)).isTrue();
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
