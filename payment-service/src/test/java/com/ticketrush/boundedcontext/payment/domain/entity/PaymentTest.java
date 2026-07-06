package com.ticketrush.boundedcontext.payment.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

  private Payment payment(PaymentStatus status) {
    return Payment.builder()
        .bookingId(100L)
        .userId(10L)
        .seatId(200L)
        .provider(PaymentProvider.TOSS)
        .amount(55_000L)
        .status(status)
        .paymentKey("pgKey_xyz")
        .approvalNumber("approval-1")
        .paidAt(LocalDateTime.of(2026, 5, 22, 10, 0))
        .build();
  }

  @Test
  @DisplayName("COMPLETED 결제는 markCanceled로 CANCELED 상태로 전이한다")
  void markCanceled_transitions_from_completed() {
    // given
    Payment payment = payment(PaymentStatus.COMPLETED);

    // when
    payment.markCanceled();

    // then
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
  }

  @Test
  @DisplayName("이미 CANCELED 된 결제에 markCanceled를 호출하면 IllegalStateException을 던진다")
  void markCanceled_rejects_already_canceled() {
    // given
    Payment payment = payment(PaymentStatus.CANCELED);

    // expect
    assertThatThrownBy(payment::markCanceled).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("COMPLETED가 아닌 상태(PENDING)에서 markCanceled를 호출하면 IllegalStateException을 던진다")
  void markCanceled_rejects_non_completed() {
    // given
    Payment payment = payment(PaymentStatus.PENDING);

    // expect
    assertThatThrownBy(payment::markCanceled).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("COMPLETED 결제는 isAlreadyProcessed가 true다")
  void isAlreadyProcessed_true_when_completed() {
    // given
    Payment payment = payment(PaymentStatus.COMPLETED);

    // expect
    assertThat(payment.isAlreadyProcessed()).isTrue();
  }

  @Test
  @DisplayName("COMPLETED가 아닌 결제는 isAlreadyProcessed가 false다")
  void isAlreadyProcessed_false_when_not_completed() {
    // expect
    assertThat(payment(PaymentStatus.PENDING).isAlreadyProcessed()).isFalse();
    assertThat(payment(PaymentStatus.CANCELED).isAlreadyProcessed()).isFalse();
    assertThat(payment(PaymentStatus.FAILED).isAlreadyProcessed()).isFalse();
  }

  @Test
  @DisplayName("failed 팩토리는 FAILED 상태로 실패 코드/사유를 담고 paymentKey는 비운다")
  void failed_creates_failed_payment_with_meta() {
    // when
    Payment failed =
        Payment.failed(
            100L, 10L, 200L, PaymentProvider.TOSS, 55_000L, "PAYMENT_400_003", "결제가 거절되었습니다.");

    // then
    assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
    assertThat(failed.getFailureCode()).isEqualTo("PAYMENT_400_003");
    assertThat(failed.getFailureReason()).isEqualTo("결제가 거절되었습니다.");
    assertThat(failed.getBookingId()).isEqualTo(100L);
    assertThat(failed.getUserId()).isEqualTo(10L);
    assertThat(failed.getSeatId()).isEqualTo(200L);
    assertThat(failed.getAmount()).isEqualTo(55_000L);
    // 결제가 성립하지 않았으므로 결제 성립 관련 필드는 비운다.
    assertThat(failed.getPaymentKey()).isNull();
    assertThat(failed.getApprovalNumber()).isNull();
    assertThat(failed.getPaidAt()).isNull();
    assertThat(failed.isAlreadyProcessed()).isFalse();
  }
}
