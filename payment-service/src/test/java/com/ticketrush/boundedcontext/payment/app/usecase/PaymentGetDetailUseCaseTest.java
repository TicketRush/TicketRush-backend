package com.ticketrush.boundedcontext.payment.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentDetailResponse;
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

@ExtendWith(MockitoExtension.class)
class PaymentGetDetailUseCaseTest {

  @Mock private PaymentRepository paymentRepository;
  @Mock private RefundRepository refundRepository;

  @InjectMocks private PaymentGetDetailUseCase paymentGetDetailUseCase;

  @Test
  @DisplayName("본인 결제이면 상세를 반환하고 환불이 있으면 함께 포함한다")
  void execute_returns_detail_with_refund() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    Long bookingId = 100L;
    Payment payment = samplePayment(paymentId, userId, bookingId);
    Refund refund = sampleRefund(7L, bookingId);
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(refundRepository.findByBookingId(bookingId)).willReturn(Optional.of(refund));

    // when
    PaymentDetailResponse response = paymentGetDetailUseCase.execute(userId, paymentId);

    // then
    assertThat(response.paymentId()).isEqualTo(paymentId);
    assertThat(response.bookingId()).isEqualTo(bookingId);
    assertThat(response.paymentKey()).isEqualTo("pgKey_xyz");
    assertThat(response.refund()).isNotNull();
    assertThat(response.refund().refundId()).isEqualTo(7L);
    assertThat(response.refund().status()).isEqualTo(RefundStatus.COMPLETED);
  }

  @Test
  @DisplayName("환불이 없으면 refund 필드는 null이다")
  void execute_returns_detail_without_refund() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    Long bookingId = 100L;
    Payment payment = samplePayment(paymentId, userId, bookingId);
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.of(payment));
    given(refundRepository.findByBookingId(bookingId)).willReturn(Optional.empty());

    // when
    PaymentDetailResponse response = paymentGetDetailUseCase.execute(userId, paymentId);

    // then
    assertThat(response.refund()).isNull();
  }

  @Test
  @DisplayName("본인 결제가 아니거나 존재하지 않으면 PAYMENT_404_002 예외가 발생한다")
  void execute_fail_when_not_owned_or_missing() {
    // given
    Long userId = 10L;
    Long paymentId = 999L;
    given(paymentRepository.findByIdAndUserId(paymentId, userId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> paymentGetDetailUseCase.execute(userId, paymentId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_NOT_FOUND);

    verifyNoInteractions(refundRepository);
  }

  private Payment samplePayment(Long id, Long userId, Long bookingId) throws Exception {
    Payment payment =
        Payment.builder()
            .bookingId(bookingId)
            .userId(userId)
            .provider(PaymentProvider.TOSS)
            .amount(55_000L)
            .status(PaymentStatus.COMPLETED)
            .paymentKey("pgKey_xyz")
            .approvalNumber("APR-001")
            .paidAt(LocalDateTime.of(2026, 5, 1, 10, 0))
            .build();
    setId(payment, id);
    return payment;
  }

  private Refund sampleRefund(Long id, Long bookingId) throws Exception {
    Refund refund =
        Refund.builder()
            .bookingId(bookingId)
            .price(55_000L)
            .status(RefundStatus.COMPLETED)
            .confirmedAt(LocalDateTime.of(2026, 5, 2, 12, 0))
            .build();
    setId(refund, id);
    return refund;
  }

  private void setId(Object entity, Long id) throws Exception {
    Field idField = entity.getClass().getSuperclass().getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(entity, id);
  }
}
