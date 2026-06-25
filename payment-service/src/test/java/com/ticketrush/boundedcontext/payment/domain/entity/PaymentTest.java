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
}
