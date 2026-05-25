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
class PaymentApprovalClientRouterTest {

  @Mock private PaymentApprovalClient tossClient;
  @Mock private PaymentApprovalClient stubClient;

  @Test
  @DisplayName("provider를 지원하는 첫 번째 클라이언트로 위임한다")
  void approve_delegates_to_supporting_client() {
    given(tossClient.supports(PaymentProvider.TOSS)).willReturn(true);
    PaymentApprovalResponse response =
        new PaymentApprovalResponse("APR-1", 1_000L, LocalDateTime.now());
    given(tossClient.approve(any())).willReturn(response);

    PaymentApprovalClientRouter router = new PaymentApprovalClientRouter(List.of(tossClient));

    PaymentApprovalResponse actual =
        router.approve(
            new PaymentApprovalRequest(PaymentProvider.TOSS, "key", "BKG-0000001", 1L, 1_000L));

    assertThat(actual).isEqualTo(response);
  }

  @Test
  @DisplayName("등록 순서가 빠른 클라이언트가 우선 매칭된다 (Stub은 fallback)")
  void approve_picks_first_supporting_client() {
    given(tossClient.supports(PaymentProvider.TOSS)).willReturn(true);
    PaymentApprovalResponse response =
        new PaymentApprovalResponse("APR-1", 1_000L, LocalDateTime.now());
    given(tossClient.approve(any())).willReturn(response);

    PaymentApprovalClientRouter router =
        new PaymentApprovalClientRouter(List.of(tossClient, stubClient));

    router.approve(
        new PaymentApprovalRequest(PaymentProvider.TOSS, "key", "BKG-0000001", 1L, 1_000L));
  }

  @Test
  @DisplayName("지원하는 클라이언트가 없으면 PAYMENT_400_002 예외가 발생한다")
  void approve_throws_when_no_client_supports_provider() {
    given(tossClient.supports(PaymentProvider.KAKAO)).willReturn(false);

    PaymentApprovalClientRouter router = new PaymentApprovalClientRouter(List.of(tossClient));

    assertThatThrownBy(
            () ->
                router.approve(
                    new PaymentApprovalRequest(
                        PaymentProvider.KAKAO, "key", "BKG-0000001", 1L, 1_000L)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PROVIDER_NOT_SUPPORTED);
  }
}
