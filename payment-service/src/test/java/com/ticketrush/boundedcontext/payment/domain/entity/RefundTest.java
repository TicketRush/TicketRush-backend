package com.ticketrush.boundedcontext.payment.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundTest {

  private static final Long PAYMENT_ID = 1L;
  private static final Long BOOKING_ID = 100L;
  private static final Long PRICE = 55_000L;

  @Test
  @DisplayName("completed 팩토리는 COMPLETED 상태의 환불 이력을 만든다")
  void completed_factory_builds_completed_refund() {
    LocalDateTime requestedAt = LocalDateTime.of(2026, 5, 22, 10, 0);
    LocalDateTime confirmedAt = LocalDateTime.of(2026, 5, 22, 10, 1);

    Refund refund =
        Refund.completed(
            PAYMENT_ID, BOOKING_ID, PRICE, "PG-REFUND-1", "사용자 예매 취소", requestedAt, confirmedAt);

    assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
    assertThat(refund.getPaymentId()).isEqualTo(PAYMENT_ID);
    assertThat(refund.getBookingId()).isEqualTo(BOOKING_ID);
    assertThat(refund.getPrice()).isEqualTo(PRICE);
    assertThat(refund.getPgRefundKey()).isEqualTo("PG-REFUND-1");
    assertThat(refund.getReason()).isEqualTo("사용자 예매 취소");
    assertThat(refund.getConfirmedAt()).isEqualTo(confirmedAt);
  }

  @Test
  @DisplayName("failed 팩토리는 FAILED 상태로 만들고 pgRefundKey·confirmedAt은 비운다")
  void failed_factory_builds_failed_refund_without_pg_fields() {
    LocalDateTime requestedAt = LocalDateTime.of(2026, 5, 22, 10, 0);

    Refund refund = Refund.failed(PAYMENT_ID, BOOKING_ID, PRICE, "PG사 환불 처리에 실패했습니다.", requestedAt);

    assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
    assertThat(refund.getPaymentId()).isEqualTo(PAYMENT_ID);
    assertThat(refund.getBookingId()).isEqualTo(BOOKING_ID);
    assertThat(refund.getPrice()).isEqualTo(PRICE);
    assertThat(refund.getReason()).isEqualTo("PG사 환불 처리에 실패했습니다.");
    assertThat(refund.getRequestedAt()).isEqualTo(requestedAt);
    assertThat(refund.getPgRefundKey()).isNull();
    assertThat(refund.getConfirmedAt()).isNull();
  }

  @Test
  @DisplayName("markCompleted는 FAILED 환불을 COMPLETED로 전이하고 pgRefundKey·confirmedAt을 채운다")
  void markCompleted_transitions_failed_to_completed() {
    Refund refund =
        Refund.failed(
            PAYMENT_ID,
            BOOKING_ID,
            PRICE,
            "PG사 환불 처리에 실패했습니다.",
            LocalDateTime.of(2026, 5, 22, 10, 0));
    LocalDateTime confirmedAt = LocalDateTime.of(2026, 5, 23, 9, 0);

    refund.markCompleted("PG-REFUND-RETRY", "사용자 예매 취소", confirmedAt);

    assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
    assertThat(refund.getPgRefundKey()).isEqualTo("PG-REFUND-RETRY");
    assertThat(refund.getReason()).isEqualTo("사용자 예매 취소");
    assertThat(refund.getConfirmedAt()).isEqualTo(confirmedAt);
  }

  @Test
  @DisplayName("markCompleted를 FAILED가 아닌 상태에서 호출하면 IllegalStateException을 던진다")
  void markCompleted_rejects_non_failed_state() {
    Refund completed =
        Refund.completed(
            PAYMENT_ID,
            BOOKING_ID,
            PRICE,
            "PG-REFUND-1",
            "사용자 예매 취소",
            LocalDateTime.of(2026, 5, 22, 10, 0),
            LocalDateTime.of(2026, 5, 22, 10, 1));

    assertThatThrownBy(() -> completed.markCompleted("PG-REFUND-2", "재시도", LocalDateTime.now()))
        .isInstanceOf(IllegalStateException.class);
  }
}
