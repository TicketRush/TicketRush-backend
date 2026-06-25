package com.ticketrush.boundedcontext.payment.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentCancelClientRouterTest {

  @Mock private PaymentCancelClient tossClient;
  @Mock private PaymentCancelClient stubClient;

  private PaymentCancelCommand command(PaymentProvider provider) {
    return new PaymentCancelCommand(provider, "key", 1_000L, "사유", "REFUND-0000001");
  }

  @Test
  @DisplayName("provider에 매핑된 실제 클라이언트로 위임한다")
  void cancel_delegates_to_mapped_client() {
    given(tossClient.isFallback()).willReturn(false);
    given(tossClient.provider()).willReturn(PaymentProvider.TOSS);
    PaymentCancelResult result =
        new PaymentCancelResult("PG-REFUND-1", 1_000L, LocalDateTime.now());
    given(tossClient.cancel(any())).willReturn(result);

    PaymentCancelClientRouter router = new PaymentCancelClientRouter(List.of(tossClient));

    PaymentCancelResult actual = router.cancel(command(PaymentProvider.TOSS));

    assertThat(actual).isEqualTo(result);
  }

  @Test
  @DisplayName("실제 provider 구현체가 있으면 fallback(stub)보다 우선 선택된다")
  void cancel_prefers_real_client_over_fallback() {
    given(tossClient.isFallback()).willReturn(false);
    given(tossClient.provider()).willReturn(PaymentProvider.TOSS);
    given(stubClient.isFallback()).willReturn(true);
    PaymentCancelResult result =
        new PaymentCancelResult("PG-REFUND-1", 1_000L, LocalDateTime.now());
    given(tossClient.cancel(any())).willReturn(result);

    PaymentCancelClientRouter router =
        new PaymentCancelClientRouter(List.of(tossClient, stubClient));

    router.cancel(command(PaymentProvider.TOSS));

    verify(tossClient).cancel(any());
    verify(stubClient, never()).cancel(any());
  }

  @Test
  @DisplayName("매핑된 실제 구현체가 없으면 fallback(stub)으로 위임한다")
  void cancel_falls_back_to_stub_when_no_real_client() {
    given(tossClient.isFallback()).willReturn(false);
    given(tossClient.provider()).willReturn(PaymentProvider.TOSS);
    given(stubClient.isFallback()).willReturn(true);
    PaymentCancelResult result =
        new PaymentCancelResult("PG-REFUND-1", 1_000L, LocalDateTime.now());
    given(stubClient.cancel(any())).willReturn(result);

    PaymentCancelClientRouter router =
        new PaymentCancelClientRouter(List.of(tossClient, stubClient));

    PaymentCancelResult actual = router.cancel(command(PaymentProvider.KAKAO));

    assertThat(actual).isEqualTo(result);
    verify(tossClient, never()).cancel(any());
  }

  @Test
  @DisplayName("매핑된 구현체도 fallback도 없으면 PAYMENT_400_002 예외가 발생한다")
  void cancel_throws_when_no_client_and_no_fallback() {
    given(tossClient.isFallback()).willReturn(false);
    given(tossClient.provider()).willReturn(PaymentProvider.TOSS);

    PaymentCancelClientRouter router = new PaymentCancelClientRouter(List.of(tossClient));

    assertThatThrownBy(() -> router.cancel(command(PaymentProvider.KAKAO)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PROVIDER_NOT_SUPPORTED);
  }
}
