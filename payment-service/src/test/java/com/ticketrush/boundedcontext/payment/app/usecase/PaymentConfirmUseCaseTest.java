package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalClient;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalResponse;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentConfirmUseCaseTest {

  @Mock private PaymentRepository paymentRepository;
  @Mock private PaymentApprovalClient paymentApprovalClient;
  @Mock private PaymentEventPublisher paymentEventPublisher;

  @InjectMocks private PaymentConfirmUseCase paymentConfirmUseCase;

  @Test
  @DisplayName("PG 승인 성공 시 Payment를 COMPLETED 상태로 저장하고 PaymentConfirmedEvent를 발행한다")
  void execute_success() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    Long seatId = 200L;
    Long amount = 55_000L;
    LocalDateTime approvedAt = LocalDateTime.of(2026, 5, 22, 10, 0);
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, seatId, PaymentProvider.KAKAO, amount, "pgKey_xyz");

    given(paymentApprovalClient.approve(any()))
        .willReturn(new PaymentApprovalResponse("APR-123", amount, approvedAt));

    Payment savedPayment =
        Payment.builder()
            .bookingId(bookingId)
            .provider(PaymentProvider.KAKAO)
            .amount(amount)
            .status(PaymentStatus.COMPLETED)
            .paidAt(approvedAt)
            .build();
    given(paymentRepository.save(any(Payment.class))).willReturn(savedPayment);

    // when
    PaymentConfirmResponse response = paymentConfirmUseCase.execute(userId, request);

    // then
    assertThat(response.status()).isEqualTo("COMPLETED");
    assertThat(response.paidAt()).isEqualTo(approvedAt);

    verify(paymentRepository).save(any(Payment.class));
    verify(paymentEventPublisher)
        .publishConfirmed(
            eq(null), eq(bookingId), eq(seatId), eq(userId), eq(amount), eq(approvedAt));
  }

  @Test
  @DisplayName("PG 승인 금액과 요청 금액이 다르면 PAYMENT_400_001 예외가 발생하고 Payment를 저장하지 않는다")
  void execute_fail_when_amount_mismatch() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    Long seatId = 200L;
    Long requestAmount = 55_000L;
    Long approvedAmount = 50_000L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(
            bookingId, seatId, PaymentProvider.KAKAO, requestAmount, "pgKey_xyz");

    given(paymentApprovalClient.approve(any()))
        .willReturn(new PaymentApprovalResponse("APR-123", approvedAmount, LocalDateTime.now()));

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_AMOUNT_MISMATCH);

    verify(paymentRepository, never()).save(any(Payment.class));
    verify(paymentEventPublisher, never())
        .publishConfirmed(any(), any(), any(), any(), any(), any());
  }
}
