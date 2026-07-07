package com.ticketrush.boundedcontext.payment.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubPaymentInquiryClientTest {

  private final StubPaymentInquiryClient client = new StubPaymentInquiryClient();

  @Test
  @DisplayName("항상 존재(DONE) 결과를 반환한다")
  void inquire_always_returns_present() {
    Optional<PaymentInquiryResult> result = client.inquire("tossKey_abc");

    assertThat(result).isPresent();
    assertThat(result.get().paymentKey()).isEqualTo("tossKey_abc");
    assertThat(result.get().status()).isEqualTo("DONE");
  }

  @Test
  @DisplayName("fallback stub이며 단일 provider에 매핑되지 않는다")
  void is_fallback_and_has_no_provider() {
    assertThat(client.isFallback()).isTrue();
    assertThatThrownBy(client::provider).isInstanceOf(UnsupportedOperationException.class);
  }
}
