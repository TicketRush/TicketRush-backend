package com.ticketrush.boundedcontext.payment.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

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

  @Test
  @DisplayName("provider를 지원하는 첫 번째 클라이언트로 위임한다")
  void cancel_delegates_to_supporting_client() {
    given(tossClient.supports(PaymentProvider.TOSS)).willReturn(true);
    PaymentCancelResult result =
        new PaymentCancelResult("PG-REFUND-1", 1_000L, LocalDateTime.now());
    given(tossClient.cancel(any())).willReturn(result);

    PaymentCancelClientRouter router = new PaymentCancelClientRouter(List.of(tossClient));

    PaymentCancelResult actual =
        router.cancel(
            new PaymentCancelCommand(PaymentProvider.TOSS, "key", 1_000L, "사유", "REFUND-0000001"));

    assertThat(actual).isEqualTo(result);
  }

  @Test
  @DisplayName("등록 순서가 빠른 클라이언트가 우선 매칭된다 (Stub은 fallback)")
  void cancel_picks_first_supporting_client() {
    given(tossClient.supports(PaymentProvider.TOSS)).willReturn(true);
    PaymentCancelResult result =
        new PaymentCancelResult("PG-REFUND-1", 1_000L, LocalDateTime.now());
    given(tossClient.cancel(any())).willReturn(result);

    PaymentCancelClientRouter router =
        new PaymentCancelClientRouter(List.of(tossClient, stubClient));

    router.cancel(
        new PaymentCancelCommand(PaymentProvider.TOSS, "key", 1_000L, "사유", "REFUND-0000001"));
  }

  @Test
  @DisplayName("지원하는 클라이언트가 없으면 PAYMENT_400_002 예외가 발생한다")
  void cancel_throws_when_no_client_supports_provider() {
    given(tossClient.supports(PaymentProvider.KAKAO)).willReturn(false);

    PaymentCancelClientRouter router = new PaymentCancelClientRouter(List.of(tossClient));

    assertThatThrownBy(
            () ->
                router.cancel(
                    new PaymentCancelCommand(
                        PaymentProvider.KAKAO, "key", 1_000L, "사유", "REFUND-0000001")))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PROVIDER_NOT_SUPPORTED);
  }
}
