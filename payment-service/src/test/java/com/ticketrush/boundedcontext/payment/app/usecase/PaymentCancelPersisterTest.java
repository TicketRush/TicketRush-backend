package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.payment.app.usecase.PaymentCancelPersister.CancelPersisted;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class PaymentCancelPersisterTest {

  @Mock private PaymentRepository paymentRepository;
  @Mock private RefundRepository refundRepository;

  @InjectMocks private PaymentCancelPersister paymentCancelPersister;

  @Test
  @DisplayName("환불을 저장하고 결제를 CANCELED로 전이해 결과를 반환한다")
  void persist_saves_refund_and_cancels_payment() throws Exception {
    // given
    Long paymentId = 1L;
    Payment payment = completedPayment(paymentId);
    Refund refund = refund(paymentId);
    given(paymentRepository.findById(paymentId)).willReturn(Optional.of(payment));
    given(refundRepository.saveAndFlush(refund))
        .willAnswer(
            invocation -> {
              setId(invocation.getArgument(0), 999L);
              return invocation.getArgument(0);
            });

    // when
    CancelPersisted result = paymentCancelPersister.persist(paymentId, refund);

    // then
    assertThat(result.payment().getStatus()).isEqualTo(PaymentStatus.CANCELED);
    assertThat(result.refund().getId()).isEqualTo(999L);
    verify(refundRepository).saveAndFlush(refund);
  }

  @Test
  @DisplayName("paymentId unique 위반은 상태 전이 이전에 전파되고 결제는 CANCELED로 바뀌지 않는다")
  void persist_propagates_unique_violation_before_state_transition() throws Exception {
    // given
    Long paymentId = 1L;
    Payment payment = completedPayment(paymentId);
    Refund refund = refund(paymentId);
    given(paymentRepository.findById(paymentId)).willReturn(Optional.of(payment));
    given(refundRepository.saveAndFlush(refund))
        .willThrow(new DataIntegrityViolationException("duplicate paymentId"));

    // when & then
    assertThatThrownBy(() -> paymentCancelPersister.persist(paymentId, refund))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  @DisplayName("결제가 존재하지 않으면 PAYMENT_404_002 예외가 발생한다")
  void persist_fail_when_payment_not_found() {
    // given
    Long paymentId = 1L;
    Refund refund = refund(paymentId);
    given(paymentRepository.findById(paymentId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> paymentCancelPersister.persist(paymentId, refund))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_NOT_FOUND);

    verify(refundRepository, never()).saveAndFlush(any(Refund.class));
  }

  @Test
  @DisplayName("이전 FAILED 환불 이력이 있으면 새 INSERT 대신 COMPLETED로 전이하고 결제를 CANCELED로 바꾼다")
  void persist_transitions_existing_failed_refund() throws Exception {
    // given: 이전 시도에서 FAILED row가 unique(payment_id) 슬롯을 점유한 상태에서 재시도가 성공한 상황
    Long paymentId = 1L;
    Payment payment = completedPayment(paymentId);
    final Refund incoming = refund(paymentId); // UseCase가 만든 COMPLETED 환불
    Refund existingFailed =
        Refund.failed(
            paymentId, 100L, 55_000L, "PG사 환불 처리에 실패했습니다.", LocalDateTime.of(2026, 5, 22, 10, 0));
    setId(existingFailed, 777L);
    given(paymentRepository.findById(paymentId)).willReturn(Optional.of(payment));
    given(refundRepository.findByPaymentId(paymentId)).willReturn(Optional.of(existingFailed));

    // when
    CancelPersisted result = paymentCancelPersister.persist(paymentId, incoming);

    // then: 새 INSERT 없이 기존 row를 전이(unique 슬롯 재사용)하고 결제는 CANCELED로 전이한다.
    assertThat(result.refund()).isSameAs(existingFailed);
    assertThat(result.refund().getStatus()).isEqualTo(RefundStatus.COMPLETED);
    assertThat(result.payment().getStatus()).isEqualTo(PaymentStatus.CANCELED);
    verify(refundRepository, never()).saveAndFlush(any(Refund.class));
  }

  @Test
  @DisplayName("persistFailedRefund는 FAILED 환불만 저장하고 결제 상태 전이는 하지 않는다")
  void persistFailedRefund_saves_only_failed_refund() {
    // given
    Refund failed = Refund.failed(1L, 100L, 55_000L, "PG사 환불 처리에 실패했습니다.", LocalDateTime.now());

    // when
    paymentCancelPersister.persistFailedRefund(failed);

    // then: 환불 저장만 수행하고 결제 조회/전이는 일어나지 않는다(Payment는 COMPLETED 유지).
    verify(refundRepository).saveAndFlush(failed);
    verify(paymentRepository, never()).findById(any());
    assertThat(failed.getStatus()).isEqualTo(RefundStatus.FAILED);
  }

  @Test
  @DisplayName("persistFailedRefund의 paymentId unique 위반은 호출자(FailedRefundRecorder)로 전파된다")
  void persistFailedRefund_propagates_unique_violation() {
    // given
    Refund failed = Refund.failed(1L, 100L, 55_000L, "PG사 환불 처리에 실패했습니다.", LocalDateTime.now());
    given(refundRepository.saveAndFlush(failed))
        .willThrow(new DataIntegrityViolationException("duplicate paymentId"));

    // when & then: 트랜잭션 경계 밖에서 멱등 처리하도록 예외를 그대로 전파한다.
    assertThatThrownBy(() -> paymentCancelPersister.persistFailedRefund(failed))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private Payment completedPayment(Long paymentId) throws Exception {
    Payment payment =
        Payment.builder()
            .bookingId(100L)
            .userId(10L)
            .seatId(200L)
            .provider(PaymentProvider.TOSS)
            .amount(55_000L)
            .status(PaymentStatus.COMPLETED)
            .paymentKey("pgKey_xyz")
            .approvalNumber("APR-1")
            .paidAt(LocalDateTime.of(2026, 5, 1, 10, 0))
            .build();
    setId(payment, paymentId);
    return payment;
  }

  private Refund refund(Long paymentId) {
    return Refund.builder()
        .paymentId(paymentId)
        .bookingId(100L)
        .price(55_000L)
        .status(RefundStatus.COMPLETED)
        .pgRefundKey("PG-REFUND-1")
        .reason("단순 변심")
        .requestedAt(LocalDateTime.of(2026, 5, 22, 10, 0))
        .confirmedAt(LocalDateTime.of(2026, 5, 22, 10, 0))
        .build();
  }

  private void setId(Object entity, Long id) throws Exception {
    Field idField = entity.getClass().getSuperclass().getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(entity, id);
  }
}
