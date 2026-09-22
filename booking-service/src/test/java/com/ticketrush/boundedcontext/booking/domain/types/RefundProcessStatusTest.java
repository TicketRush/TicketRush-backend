package com.ticketrush.boundedcontext.booking.domain.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RefundProcessStatusTest {

  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 7, 10, 12, 0);

  @Test
  @DisplayName("성공: REFUNDING은 진행 중, REFUNDED는 완료, CONFIRMED + 실패 이력은 실패로 파생된다")
  void from_derives_status_from_booking_state() {
    assertThat(RefundProcessStatus.from(BookingStatus.REFUNDING, null))
        .isEqualTo(RefundProcessStatus.IN_PROGRESS);
    assertThat(RefundProcessStatus.from(BookingStatus.REFUNDED, null))
        .isEqualTo(RefundProcessStatus.COMPLETED);
    assertThat(RefundProcessStatus.from(BookingStatus.CONFIRMED, FAILED_AT))
        .isEqualTo(RefundProcessStatus.FAILED);
  }

  @Test
  @DisplayName("성공: 실패 이력이 남은 재시도 건은 실패가 아니라 현재 상태로 분류된다")
  void from_classifies_retried_booking_by_current_status() {
    // given: refundFailedAt은 재환불 시 지워지지 않는다(ADR 0005). 실패 시각을 먼저 보면
    // 재시도 중이거나 이미 환불된 예매가 실패로 잡혀 화면에서 중복·오분류가 난다.
    assertThat(RefundProcessStatus.from(BookingStatus.REFUNDING, FAILED_AT))
        .isEqualTo(RefundProcessStatus.IN_PROGRESS);
    assertThat(RefundProcessStatus.from(BookingStatus.REFUNDED, FAILED_AT))
        .isEqualTo(RefundProcessStatus.COMPLETED);
  }

  @Test
  @DisplayName("실패: 실패 이력이 없는 CONFIRMED는 환불 대상이 아니다")
  void from_rejects_confirmed_without_failure_history() {
    // given: 정상 확정 예매다. null을 돌려주면 화면이 빈 칸으로 거짓말을 하므로 크게 터뜨린다.
    assertThatThrownBy(() -> RefundProcessStatus.from(BookingStatus.CONFIRMED, null))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = BookingStatus.class,
      names = {"PENDING", "CANCELED", "EXPIRED"})
  @DisplayName("실패: 결제 전 취소·대기·만료는 실패 시각이 있어도 환불 대상이 아니다")
  void from_rejects_non_refund_statuses(BookingStatus bookingStatus) {
    // given: 조회 조건과 파생 규칙이 갈라진 경우에만 도달한다. 조용히 통과시키면
    // CANCELED가 환불로 집계되는 회귀가 화면까지 그대로 흘러간다.
    assertThatThrownBy(() -> RefundProcessStatus.from(bookingStatus, FAILED_AT))
        .isInstanceOf(IllegalStateException.class);
  }
}
