package com.ticketrush.boundedcontext.payment.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubPaymentApprovalClientTest {

  private final StubPaymentApprovalClient client = new StubPaymentApprovalClient();

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
  @DisplayName("요청 금액 그대로 승인 응답을 만든다")
  void approve_returns_response_with_request_amount() {
    PaymentApprovalResponse response =
        client.approve(
            new PaymentApprovalRequest(
                PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000001", 1L, 1_000L));

    assertThat(response.approvedAmount()).isEqualTo(1_000L);
    assertThat(response.approvalNumber()).isNotBlank();
    assertThat(response.approvedAt()).isNotNull();
  }
}
