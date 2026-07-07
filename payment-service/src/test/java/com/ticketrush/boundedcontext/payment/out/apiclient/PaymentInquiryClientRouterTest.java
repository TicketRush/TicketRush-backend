package com.ticketrush.boundedcontext.payment.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentInquiryClientRouterTest {

  private static final String PAYMENT_KEY = "tossKey_abc";

  @Mock private PaymentInquiryClient tossClient;
  @Mock private PaymentInquiryClient stubClient;
  @Mock private PaymentInquiryClient duplicateTossClient;

  private Optional<PaymentInquiryResult> result() {
    return Optional.of(new PaymentInquiryResult(PAYMENT_KEY, "BKG-0000100", 55_000L, "DONE", null));
  }

  @Test
  @DisplayName("provider에 매핑된 실제 클라이언트로 위임한다")
  void inquire_delegates_to_mapped_client() {
    given(tossClient.isFallback()).willReturn(false);
    given(tossClient.provider()).willReturn(PaymentProvider.TOSS);
    given(tossClient.inquire(PAYMENT_KEY)).willReturn(result());

    PaymentInquiryClientRouter router = new PaymentInquiryClientRouter(List.of(tossClient));

    Optional<PaymentInquiryResult> actual = router.inquire(PaymentProvider.TOSS, PAYMENT_KEY);

    assertThat(actual).isEqualTo(result());
  }

  @Test
  @DisplayName("실제 provider 구현체가 있으면 fallback(stub)보다 우선 선택된다")
  void inquire_prefers_real_client_over_fallback() {
    given(tossClient.isFallback()).willReturn(false);
    given(tossClient.provider()).willReturn(PaymentProvider.TOSS);
    given(stubClient.isFallback()).willReturn(true);
    given(tossClient.inquire(PAYMENT_KEY)).willReturn(result());

    PaymentInquiryClientRouter router =
        new PaymentInquiryClientRouter(List.of(tossClient, stubClient));

    router.inquire(PaymentProvider.TOSS, PAYMENT_KEY);

    verify(tossClient).inquire(PAYMENT_KEY);
    verify(stubClient, never()).inquire(PAYMENT_KEY);
  }

  @Test
  @DisplayName("매핑된 실제 구현체가 없으면 fallback(stub)으로 위임한다")
  void inquire_falls_back_to_stub_when_no_real_client() {
    given(tossClient.isFallback()).willReturn(false);
    given(tossClient.provider()).willReturn(PaymentProvider.TOSS);
    given(stubClient.isFallback()).willReturn(true);
    given(stubClient.inquire(PAYMENT_KEY)).willReturn(result());

    PaymentInquiryClientRouter router =
        new PaymentInquiryClientRouter(List.of(tossClient, stubClient));

    Optional<PaymentInquiryResult> actual = router.inquire(PaymentProvider.KAKAO, PAYMENT_KEY);

    assertThat(actual).isEqualTo(result());
    verify(tossClient, never()).inquire(PAYMENT_KEY);
  }

  @Test
  @DisplayName("매핑된 구현체도 fallback도 없으면 PAYMENT_400_002 예외가 발생한다")
  void inquire_throws_when_no_client_and_no_fallback() {
    given(tossClient.isFallback()).willReturn(false);
    given(tossClient.provider()).willReturn(PaymentProvider.TOSS);

    PaymentInquiryClientRouter router = new PaymentInquiryClientRouter(List.of(tossClient));

    assertThatThrownBy(() -> router.inquire(PaymentProvider.KAKAO, PAYMENT_KEY))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PROVIDER_NOT_SUPPORTED);
  }

  @Test
  @DisplayName("같은 provider 구현체가 둘 이상이면 기동(생성자) 시점에 실패한다")
  void fails_fast_on_duplicate_provider() {
    given(tossClient.isFallback()).willReturn(false);
    given(tossClient.provider()).willReturn(PaymentProvider.TOSS);
    given(duplicateTossClient.isFallback()).willReturn(false);
    given(duplicateTossClient.provider()).willReturn(PaymentProvider.TOSS);

    assertThatThrownBy(
            () -> new PaymentInquiryClientRouter(List.of(tossClient, duplicateTossClient)))
        .isInstanceOf(IllegalStateException.class);
  }
}
