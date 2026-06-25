package com.ticketrush.boundedcontext.payment.app.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentCancelRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentCancelResponse;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentCancelUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentConfirmUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentGetDetailUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentGetListUseCase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class PaymentFacadeTest {

  @Mock private PaymentConfirmUseCase paymentConfirmUseCase;
  @Mock private PaymentCancelUseCase paymentCancelUseCase;
  @Mock private PaymentGetListUseCase paymentGetListUseCase;
  @Mock private PaymentGetDetailUseCase paymentGetDetailUseCase;

  @InjectMocks private PaymentFacade paymentFacade;

  @Test
  @DisplayName("cancel 정상 처리 시 UseCase 결과를 그대로 반환하고 멱등 조회는 호출하지 않는다")
  void cancel_returns_usecase_result() {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");
    PaymentCancelResponse expected =
        new PaymentCancelResponse(paymentId, "CANCELED", 999L, 55_000L, LocalDateTime.now());
    given(paymentCancelUseCase.execute(userId, paymentId, request)).willReturn(expected);

    // when
    PaymentCancelResponse response = paymentFacade.cancel(userId, paymentId, request);

    // then
    assertThat(response).isSameAs(expected);
    verify(paymentCancelUseCase, never()).getCanceledResponse(any(), any());
  }

  @Test
  @DisplayName("동시 취소로 unique 제약이 위반되면 먼저 확정된 환불을 멱등 반환한다")
  void cancel_falls_back_to_existing_refund_on_constraint_violation() {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");
    PaymentCancelResponse existing =
        new PaymentCancelResponse(paymentId, "CANCELED", 999L, 55_000L, LocalDateTime.now());
    given(paymentCancelUseCase.execute(userId, paymentId, request))
        .willThrow(new DataIntegrityViolationException("duplicate paymentId"));
    given(paymentCancelUseCase.getCanceledResponse(userId, paymentId)).willReturn(existing);

    // when
    PaymentCancelResponse response = paymentFacade.cancel(userId, paymentId, request);

    // then
    assertThat(response).isSameAs(existing);
    verify(paymentCancelUseCase).getCanceledResponse(eq(userId), eq(paymentId));
  }
}
