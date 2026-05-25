package com.ticketrush.boundedcontext.payment.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubPaymentApprovalClientTest {

  private final StubPaymentApprovalClient client = new StubPaymentApprovalClient();

  @Test
  @DisplayName("모든 provider를 지원한다 (fallback 클라이언트)")
  void supports_all_providers() {
    assertThat(client.supports(PaymentProvider.TOSS)).isTrue();
    assertThat(client.supports(PaymentProvider.KAKAO)).isTrue();
    assertThat(client.supports(PaymentProvider.NAVER)).isTrue();
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
