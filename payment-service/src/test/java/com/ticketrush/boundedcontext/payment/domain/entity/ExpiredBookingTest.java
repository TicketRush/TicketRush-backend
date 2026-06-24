package com.ticketrush.boundedcontext.payment.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExpiredBookingTest {

  @Test
  @DisplayName("bookingId와 expiredAt로 ExpiredBooking을 생성한다")
  void of_creates() {
    // given
    Long bookingId = 100L;
    LocalDateTime expiredAt = LocalDateTime.of(2026, 6, 21, 10, 0);

    // when
    ExpiredBooking expiredBooking = ExpiredBooking.of(bookingId, expiredAt);

    // then
    assertThat(expiredBooking.getBookingId()).isEqualTo(bookingId);
    assertThat(expiredBooking.getExpiredAt()).isEqualTo(expiredAt);
  }

  @Test
  @DisplayName("bookingId가 null이면 생성에 실패한다")
  void of_rejects_null_bookingId() {
    assertThatThrownBy(() -> ExpiredBooking.of(null, LocalDateTime.of(2026, 6, 21, 10, 0)))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("expiredAt이 null이면 생성에 실패한다")
  void of_rejects_null_expiredAt() {
    assertThatThrownBy(() -> ExpiredBooking.of(100L, null))
        .isInstanceOf(NullPointerException.class);
  }
}
