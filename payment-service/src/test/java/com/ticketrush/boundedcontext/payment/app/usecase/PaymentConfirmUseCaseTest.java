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
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalClientRouter;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalRequest;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalResponse;
import com.ticketrush.boundedcontext.payment.out.repository.ExpiredBookingRepository;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentConfirmUseCaseTest {

  @Mock private PaymentRepository paymentRepository;
  @Mock private PaymentApprovalClientRouter paymentApprovalClientRouter;
  @Mock private PaymentEventPublisher paymentEventPublisher;
  @Mock private ExpiredBookingRepository expiredBookingRepository;

  @InjectMocks private PaymentConfirmUseCase paymentConfirmUseCase;

  @Test
  @DisplayName("PG 승인 성공 시 Payment를 COMPLETED 상태로 저장하고 PaymentConfirmedEvent를 발행한다")
  void execute_success() throws Exception {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    Long seatId = 200L;
    Long amount = 55_000L;
    String paymentKey = "pgKey_xyz";
    String approvalNumber = "APR-123";
    Long savedPaymentId = 999L;
    LocalDateTime approvedAt = LocalDateTime.of(2025, 1, 15, 10, 0);
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, seatId, PaymentProvider.TOSS, amount, paymentKey);

    given(paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED))
        .willReturn(false);
    given(paymentApprovalClientRouter.approve(any()))
        .willReturn(new PaymentApprovalResponse(approvalNumber, amount, approvedAt));
    given(paymentRepository.save(any(Payment.class)))
        .willAnswer(
            invocation -> {
              Payment p = invocation.getArgument(0);
              setId(p, savedPaymentId);
              return p;
            });

    // when
    PaymentConfirmResponse response = paymentConfirmUseCase.execute(userId, request);

    // then
    assertThat(response.paymentId()).isEqualTo(savedPaymentId);
    assertThat(response.status()).isEqualTo("COMPLETED");
    assertThat(response.paidAt()).isEqualTo(approvedAt);

    ArgumentCaptor<PaymentApprovalRequest> approvalCaptor =
        ArgumentCaptor.forClass(PaymentApprovalRequest.class);
    verify(paymentApprovalClientRouter).approve(approvalCaptor.capture());
    PaymentApprovalRequest sentApproval = approvalCaptor.getValue();
    assertThat(sentApproval.provider()).isEqualTo(PaymentProvider.TOSS);
    assertThat(sentApproval.paymentKey()).isEqualTo(paymentKey);
    assertThat(sentApproval.orderId()).isEqualTo("BKG-0000100");
    assertThat(sentApproval.bookingId()).isEqualTo(bookingId);
    assertThat(sentApproval.amount()).isEqualTo(amount);

    ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).save(paymentCaptor.capture());
    Payment savedPayment = paymentCaptor.getValue();
    assertThat(savedPayment.getBookingId()).isEqualTo(bookingId);
    assertThat(savedPayment.getUserId()).isEqualTo(userId);
    assertThat(savedPayment.getProvider()).isEqualTo(PaymentProvider.TOSS);
    assertThat(savedPayment.getAmount()).isEqualTo(amount);
    assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(savedPayment.getPaymentKey()).isEqualTo(paymentKey);
    assertThat(savedPayment.getApprovalNumber()).isEqualTo(approvalNumber);
    assertThat(savedPayment.getPaidAt()).isEqualTo(approvedAt);

    verify(paymentEventPublisher)
        .publishConfirmed(
            eq(savedPaymentId), eq(bookingId), eq(seatId), eq(userId), eq(amount), eq(approvedAt));
  }

  private void setId(Payment payment, Long id) throws Exception {
    Field idField = payment.getClass().getSuperclass().getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(payment, id);
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

    given(paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED))
        .willReturn(false);
    given(paymentApprovalClientRouter.approve(any()))
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

  @Test
  @DisplayName("동일 bookingId의 COMPLETED 결제가 이미 존재하면 PAYMENT_409_001 예외가 발생한다")
  void execute_fail_when_already_completed() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.KAKAO, 55_000L, "pgKey_xyz");

    given(paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED))
        .willReturn(true);

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_ALREADY_COMPLETED);

    verify(paymentApprovalClientRouter, never()).approve(any());
    verify(paymentRepository, never()).save(any(Payment.class));
    verify(paymentEventPublisher, never())
        .publishConfirmed(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("만료된 booking이면 BOOKING_409_003 예외가 발생하고 PG 승인을 호출하지 않는다")
  void execute_fail_when_booking_expired() {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    PaymentConfirmRequest request =
        new PaymentConfirmRequest(bookingId, 200L, PaymentProvider.KAKAO, 55_000L, "pgKey_xyz");

    given(paymentRepository.existsByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED))
        .willReturn(false);
    given(expiredBookingRepository.existsByBookingId(bookingId)).willReturn(true);

    // when & then
    assertThatThrownBy(() -> paymentConfirmUseCase.execute(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.BOOKING_EXPIRED);

    verify(paymentApprovalClientRouter, never()).approve(any());
    verify(paymentRepository, never()).save(any(Payment.class));
    verify(paymentEventPublisher, never())
        .publishConfirmed(any(), any(), any(), any(), any(), any());
  }
}
