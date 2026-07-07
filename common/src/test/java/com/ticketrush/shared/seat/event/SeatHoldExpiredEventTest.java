package com.ticketrush.shared.seat.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeatHoldExpiredEventTest {

  @Test
  @DisplayName("DomainEvent 계약: topic/key(bookingNumber)/aggregateId(seatId)/eventName을 반환한다")
  void domainEventContract() {
    // given
    LocalDateTime expiredAt = LocalDateTime.now();
    SeatHoldExpiredEvent event = new SeatHoldExpiredEvent(7L, "BK-20260707-0001", expiredAt);

    // then
    assertThat(event.topic()).isEqualTo("seat-hold-expired-topic");
    assertThat(event.key()).isEqualTo("BK-20260707-0001"); // 파티션 키 = bookingNumber
    assertThat(event.aggregateId()).isEqualTo("7"); // Outbox aggregateType=Seat 유도용 seatId
    assertThat(event.eventName()).isEqualTo("SeatHoldExpiredEvent");
    assertThat(event.seatId()).isEqualTo(7L);
    assertThat(event.bookingNumber()).isEqualTo("BK-20260707-0001");
    assertThat(event.expiredAt()).isEqualTo(expiredAt);
  }
}
