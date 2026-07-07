package com.ticketrush.boundedcontext.seat.app.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.types.SeatStatus;
import com.ticketrush.shared.seat.event.SeatHoldExpiredEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatHoldExpiredPublisherTest {

  @Mock private EventPublisher eventPublisher;

  @Captor private ArgumentCaptor<SeatHoldExpiredEvent> eventCaptor;

  @InjectMocks private SeatHoldExpiredPublisher seatHoldExpiredPublisher;

  @Test
  @DisplayName("bookingNumber와 holdExpiredAt이 있으면 해당 값으로 SeatHoldExpiredEvent를 발행한다")
  void publish_WithBookingNumberAndExpiry() {
    // given
    LocalDateTime holdExpiredAt = LocalDateTime.now().minusMinutes(1);
    Seat seat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(holdExpiredAt)
            .bookingNumber("BK-1")
            .build();

    // when
    seatHoldExpiredPublisher.publish(seat);

    // then
    verify(eventPublisher).publish(eventCaptor.capture());
    SeatHoldExpiredEvent event = eventCaptor.getValue();
    assertThat(event.bookingNumber()).isEqualTo("BK-1");
    assertThat(event.expiredAt()).isEqualTo(holdExpiredAt);
  }

  @Test
  @DisplayName("holdExpiredAt이 null이면 현재 시각으로 대체해 발행한다")
  void publish_WhenHoldExpiredAtNull_UsesNow() {
    // given
    LocalDateTime before = LocalDateTime.now();
    Seat seat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.HOLD)
            .bookingNumber("BK-1")
            .build(); // holdExpiredAt 미설정 → null

    // when
    seatHoldExpiredPublisher.publish(seat);

    // then
    verify(eventPublisher).publish(eventCaptor.capture());
    assertThat(eventCaptor.getValue().expiredAt()).isAfterOrEqualTo(before);
  }

  @Test
  @DisplayName("bookingNumber가 null이면 발행하지 않는다")
  void publish_WhenBookingNumberNull_Skips() {
    // given
    Seat seat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.HOLD)
            .build(); // bookingNumber 미설정 → null

    // when
    seatHoldExpiredPublisher.publish(seat);

    // then
    verifyNoInteractions(eventPublisher);
  }

  @Test
  @DisplayName("bookingNumber가 공백이면 발행하지 않는다")
  void publish_WhenBookingNumberBlank_Skips() {
    // given
    Seat seat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.HOLD)
            .bookingNumber("   ")
            .build();

    // when
    seatHoldExpiredPublisher.publish(seat);

    // then
    verifyNoInteractions(eventPublisher);
  }
}
