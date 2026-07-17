package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
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
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class FailedRefundRecorderTest {

  private static final Long PAYMENT_ID = 1L;
  private static final Long BOOKING_ID = 100L;
  private static final Long AMOUNT = 55_000L;

  @Mock private PaymentCancelPersister paymentCancelPersister;

  @InjectMocks private FailedRefundRecorder failedRefundRecorder;

  @Test
  @DisplayName("PG 거절(PAYMENT_REFUND_FAILED)이면 FAILED 환불 이력을 저장한다")
  void records_failed_refund_on_pg_rejection() throws Exception {
    // given
    Payment payment = completedPayment();
    BusinessException rejected = new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED);

    // when
    failedRefundRecorder.recordIfRejected(payment, rejected);

    // then
    ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
    verify(paymentCancelPersister).persistFailedRefund(captor.capture());
    Refund saved = captor.getValue();
    assertThat(saved.getStatus()).isEqualTo(RefundStatus.FAILED);
    assertThat(saved.getPaymentId()).isEqualTo(PAYMENT_ID);
    assertThat(saved.getBookingId()).isEqualTo(BOOKING_ID);
    assertThat(saved.getPrice()).isEqualTo(AMOUNT);
    assertThat(saved.getReason()).isEqualTo(ErrorStatus.PAYMENT_REFUND_FAILED.getMessage());
    assertThat(saved.getPgRefundKey()).isNull();
    assertThat(saved.getConfirmedAt()).isNull();
  }

  @Test
  @DisplayName("PG 통신 실패(PAYMENT_PG_COMMUNICATION_FAILED)는 성공 여부 불명이라 FAILED 이력을 저장하지 않는다")
  void does_not_record_on_communication_failure() throws Exception {
    // given
    Payment payment = completedPayment();
    BusinessException communication =
        new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);

    // when
    failedRefundRecorder.recordIfRejected(payment, communication);

    // then
    verify(paymentCancelPersister, never()).persistFailedRefund(any());
  }

  @Test
  @DisplayName("이미 FAILED 이력이 있어 unique 위반이 나면 삼켜 멱등 처리한다(예외 전파 없음)")
  void swallows_unique_violation_for_idempotency() throws Exception {
    // given
    Payment payment = completedPayment();
    BusinessException rejected = new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED);
    willThrow(new DataIntegrityViolationException("duplicate paymentId"))
        .given(paymentCancelPersister)
        .persistFailedRefund(any());

    // when & then: 원 환불 실패 예외를 가리지 않도록 멱등 무시한다.
    assertThatCode(() -> failedRefundRecorder.recordIfRejected(payment, rejected))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("이력 저장 중 예기치 못한 예외가 나도 삼켜 원 환불 실패 예외를 가리지 않는다")
  void swallows_unexpected_persistence_error() throws Exception {
    // given
    Payment payment = completedPayment();
    BusinessException rejected = new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED);
    willThrow(new RuntimeException("db down"))
        .given(paymentCancelPersister)
        .persistFailedRefund(any());

    // when & then
    assertThatCode(() -> failedRefundRecorder.recordIfRejected(payment, rejected))
        .doesNotThrowAnyException();
  }

  private Payment completedPayment() throws Exception {
    Payment payment =
        Payment.builder()
            .bookingId(BOOKING_ID)
            .userId(10L)
            .seatId(200L)
            .provider(PaymentProvider.TOSS)
            .amount(AMOUNT)
            .status(PaymentStatus.COMPLETED)
            .paymentKey("pgKey_xyz")
            .approvalNumber("APR-1")
            .paidAt(LocalDateTime.of(2026, 5, 1, 10, 0))
            .build();
    setId(payment, PAYMENT_ID);
    return payment;
  }

  private void setId(Object entity, Long id) throws Exception {
    Field idField = entity.getClass().getSuperclass().getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(entity, id);
  }
}
