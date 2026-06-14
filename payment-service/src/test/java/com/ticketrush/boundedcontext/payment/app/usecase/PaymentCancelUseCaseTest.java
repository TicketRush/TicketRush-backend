package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentCancelRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentCancelResponse;
import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelClientRouter;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelCommand;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelResult;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.boundedcontext.payment.out.repository.RefundRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentCancelUseCaseTest {

  @Mock private PaymentRepository paymentRepository;
  @Mock private RefundRepository refundRepository;
  @Mock private PaymentCancelClientRouter paymentCancelClientRouter;
  @Mock private PaymentEventPublisher paymentEventPublisher;

  @InjectMocks private PaymentCancelUseCase paymentCancelUseCase;

  @Test
  @DisplayName("COMPLETED 결제 환불 성공 시 PG 취소 호출 + Refund 저장 + 결제 CANCELED 전이 + 이벤트를 발행한다")
  void execute_success() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    Long bookingId = 100L;
    Long seatId = 200L;
    Long amount = 55_000L;
    Long savedRefundId = 999L;
    LocalDateTime canceledAt = LocalDateTime.of(2026, 5, 22, 10, 0);
    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");

    Payment payment = completedPayment(paymentId, userId, bookingId, seatId, amount);
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(paymentCancelClientRouter.cancel(any()))
        .willReturn(new PaymentCancelResult("PG-REFUND-1", amount, canceledAt));
    given(refundRepository.save(any(Refund.class)))
        .willAnswer(
            invocation -> {
              Refund r = invocation.getArgument(0);
              setId(r, savedRefundId);
              return r;
            });

    // when
    PaymentCancelResponse response = paymentCancelUseCase.execute(userId, paymentId, request);

    // then
    assertThat(response.paymentId()).isEqualTo(paymentId);
    assertThat(response.status()).isEqualTo("CANCELED");
    assertThat(response.refundId()).isEqualTo(savedRefundId);
    assertThat(response.refundedAmount()).isEqualTo(amount);
    assertThat(response.canceledAt()).isEqualTo(canceledAt);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);

    ArgumentCaptor<PaymentCancelCommand> commandCaptor =
        ArgumentCaptor.forClass(PaymentCancelCommand.class);
    verify(paymentCancelClientRouter).cancel(commandCaptor.capture());
    PaymentCancelCommand command = commandCaptor.getValue();
    assertThat(command.provider()).isEqualTo(PaymentProvider.TOSS);
    assertThat(command.paymentKey()).isEqualTo("pgKey_xyz");
    assertThat(command.amount()).isEqualTo(amount);
    assertThat(command.reason()).isEqualTo("단순 변심");
    assertThat(command.idempotencyKey()).isEqualTo("REFUND-0000001");

    ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
    verify(refundRepository).save(refundCaptor.capture());
    Refund savedRefund = refundCaptor.getValue();
    assertThat(savedRefund.getPaymentId()).isEqualTo(paymentId);
    assertThat(savedRefund.getBookingId()).isEqualTo(bookingId);
    assertThat(savedRefund.getPrice()).isEqualTo(amount);
    assertThat(savedRefund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
    assertThat(savedRefund.getPgRefundKey()).isEqualTo("PG-REFUND-1");
    assertThat(savedRefund.getReason()).isEqualTo("단순 변심");
    assertThat(savedRefund.getConfirmedAt()).isEqualTo(canceledAt);

    verify(paymentEventPublisher)
        .publishCanceled(
            eq(paymentId),
            eq(bookingId),
            eq(seatId),
            eq(savedRefundId),
            eq(amount),
            eq("단순 변심"),
            eq(canceledAt));
  }

  @Test
  @DisplayName("이미 CANCELED 된 결제는 PG 재호출 없이 기존 환불 내역을 멱등하게 반환한다")
  void execute_idempotent_when_already_canceled() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");

    Payment payment = completedPayment(paymentId, userId, 100L, 200L, 55_000L);
    payment.markCanceled();
    Refund existing =
        Refund.builder()
            .paymentId(paymentId)
            .bookingId(100L)
            .price(55_000L)
            .status(RefundStatus.COMPLETED)
            .confirmedAt(LocalDateTime.of(2026, 5, 22, 10, 0))
            .build();
    setId(existing, 999L);

    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(refundRepository.findByPaymentId(paymentId)).willReturn(Optional.of(existing));

    // when
    PaymentCancelResponse response = paymentCancelUseCase.execute(userId, paymentId, request);

    // then
    assertThat(response.refundId()).isEqualTo(999L);
    assertThat(response.status()).isEqualTo("CANCELED");
    verify(paymentCancelClientRouter, never()).cancel(any());
    verify(refundRepository, never()).save(any(Refund.class));
    verify(paymentEventPublisher, never())
        .publishCanceled(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("본인 결제가 아니거나 존재하지 않으면 PAYMENT_404_002 예외가 발생한다")
  void execute_fail_when_not_found() {
    // given
    Long userId = 10L;
    Long paymentId = 999L;
    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> paymentCancelUseCase.execute(userId, paymentId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_NOT_FOUND);

    verify(paymentCancelClientRouter, never()).cancel(any());
    verify(refundRepository, never()).save(any(Refund.class));
  }

  @Test
  @DisplayName("COMPLETED/CANCELED 가 아닌 결제는 PAYMENT_409_002 예외가 발생한다")
  void execute_fail_when_not_cancelable() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");

    Payment payment =
        Payment.builder()
            .bookingId(100L)
            .userId(userId)
            .seatId(200L)
            .provider(PaymentProvider.TOSS)
            .amount(55_000L)
            .status(PaymentStatus.PENDING)
            .paymentKey("pgKey_xyz")
            .build();
    setId(payment, paymentId);
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));

    // when & then
    assertThatThrownBy(() -> paymentCancelUseCase.execute(userId, paymentId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_NOT_CANCELABLE);

    verify(paymentCancelClientRouter, never()).cancel(any());
    verify(refundRepository, never()).save(any(Refund.class));
  }

  @Test
  @DisplayName("PG 환불이 실패하면 예외가 전파되고 Refund 저장/이벤트 발행을 하지 않는다")
  void execute_fail_when_pg_refund_fails() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    PaymentCancelRequest request = new PaymentCancelRequest("단순 변심");

    Payment payment = completedPayment(paymentId, userId, 100L, 200L, 55_000L);
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(paymentCancelClientRouter.cancel(any()))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED));

    // when & then
    assertThatThrownBy(() -> paymentCancelUseCase.execute(userId, paymentId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_FAILED);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    verify(refundRepository, never()).save(any(Refund.class));
    verify(paymentEventPublisher, never())
        .publishCanceled(any(), any(), any(), any(), any(), any(), any());
  }

  private Payment completedPayment(
      Long paymentId, Long userId, Long bookingId, Long seatId, Long amount) throws Exception {
    Payment payment =
        Payment.builder()
            .bookingId(bookingId)
            .userId(userId)
            .seatId(seatId)
            .provider(PaymentProvider.TOSS)
            .amount(amount)
            .status(PaymentStatus.COMPLETED)
            .paymentKey("pgKey_xyz")
            .approvalNumber("APR-1")
            .paidAt(LocalDateTime.of(2026, 5, 1, 10, 0))
            .build();
    setId(payment, paymentId);
    return payment;
  }

  private void setId(Object entity, Long id) throws Exception {
    Field idField = entity.getClass().getSuperclass().getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(entity, id);
  }
}
