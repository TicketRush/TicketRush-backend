package com.ticketrush.boundedcontext.payment.app.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentCancelRequest;
import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentCancelResponse;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentCancelUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentConfirmUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentGetDetailUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentGetListUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentWebhookUseCase;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.nio.charset.StandardCharsets;
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
  @Mock private PaymentWebhookUseCase paymentWebhookUseCase;

  @InjectMocks private PaymentFacade paymentFacade;

  private PaymentConfirmRequest confirmRequest(String paymentKey) {
    return new PaymentConfirmRequest(100L, 200L, PaymentProvider.TOSS, 55_000L, paymentKey);
  }

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

  @Test
  @DisplayName("confirm 정상 처리 시 UseCase 결과를 그대로 반환하고 멱등 조회는 호출하지 않는다")
  void confirm_returns_usecase_result() {
    // given
    Long userId = 10L;
    PaymentConfirmRequest request = confirmRequest("pgKey_xyz");
    PaymentConfirmResponse expected =
        new PaymentConfirmResponse(1L, "COMPLETED", LocalDateTime.now());
    given(paymentConfirmUseCase.execute(userId, request)).willReturn(expected);

    // when
    PaymentConfirmResponse response = paymentFacade.confirm(userId, request);

    // then
    assertThat(response).isSameAs(expected);
    verify(paymentConfirmUseCase, never()).getConfirmedResponseByBookingId(any());
  }

  @Test
  @DisplayName("동시 confirm으로 booking unique 제약이 위반되면 먼저 확정된 결제를 멱등 반환한다")
  void confirm_falls_back_to_existing_on_constraint_violation() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request = confirmRequest("pgKey_xyz");
    PaymentConfirmResponse existing =
        new PaymentConfirmResponse(1L, "COMPLETED", LocalDateTime.now());
    given(paymentConfirmUseCase.execute(userId, request))
        .willThrow(new DataIntegrityViolationException("duplicate completed_booking_id"));
    given(paymentConfirmUseCase.getConfirmedResponseByBookingId(bookingId)).willReturn(existing);

    // when
    PaymentConfirmResponse response = paymentFacade.confirm(userId, request);

    // then
    assertThat(response).isSameAs(existing);
    verify(paymentConfirmUseCase).getConfirmedResponseByBookingId(eq(bookingId));
  }

  @Test
  @DisplayName("유니크 충돌이 아닌 무결성 위반으로 조회되지 않으면 원래 예외를 재던진다")
  void confirm_rethrows_original_when_lookup_fails() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request = confirmRequest("pgKey_xyz");
    DataIntegrityViolationException original = new DataIntegrityViolationException("not null 위반");
    given(paymentConfirmUseCase.execute(userId, request)).willThrow(original);
    given(paymentConfirmUseCase.getConfirmedResponseByBookingId(bookingId))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_NOT_FOUND));

    // when & then
    assertThatThrownBy(() -> paymentFacade.confirm(userId, request)).isSameAs(original);
  }

  @Test
  @DisplayName("handleWebhook은 webhook UseCase에 원문 body와 서명을 위임한다")
  void handleWebhook_delegates_to_usecase() {
    // given
    byte[] rawBody = "{\"eventType\":\"PAYMENT_STATUS_CHANGED\"}".getBytes(StandardCharsets.UTF_8);
    String signature = "sig";

    // when
    paymentFacade.handleWebhook(rawBody, signature);

    // then
    verify(paymentWebhookUseCase).handle(rawBody, signature);
  }
}
