package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.seat.app.support.SeatEventSource;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatReleaseSoldSeatUseCaseTest {

  @InjectMocks private SeatReleaseSoldSeatUseCase seatReleaseSoldSeatUseCase;

  @Mock private SeatRepository seatRepository;
  @Mock private SeatStatusEventPublisher seatStatusEventPublisher;

  private static final Long SEAT_ID = 1L;
  private static final String BOOKING_NUMBER = "BOOK-1234";

  private Seat seatWithStatus(SeatStatus status) {
    return Seat.builder()
        .performanceId(1L)
        .seatNumber("A-1")
        .seatStatus(status)
        .bookingNumber(status == SeatStatus.AVAILABLE ? null : BOOKING_NUMBER)
        .build();
  }

  @Test
  @DisplayName("성공: 예매 번호가 일치하는 SOLD 좌석을 AVAILABLE로 반환하고 상태 변경 이벤트를 발행한다")
  void execute_success_when_sold_and_booking_number_matches() {
    // given
    Seat seat = seatWithStatus(SeatStatus.SOLD);
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.of(seat));

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, BOOKING_NUMBER);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verify(seatStatusEventPublisher).publishAfterCommit(seat, SeatEventSource.REFUND_RELEASE);
  }

  @Test
  @DisplayName("성공: 예매 번호가 없으면(결제 취소 API #22 경로) SOLD 가드만으로 반환한다")
  void execute_success_when_booking_number_null() {
    // given: payment가 bookingNumber를 모르는 API 취소 경로는 null로 도착한다
    Seat seat = seatWithStatus(SeatStatus.SOLD);
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.of(seat));

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, null);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verify(seatStatusEventPublisher).publishAfterCommit(seat, SeatEventSource.REFUND_RELEASE);
  }

  @Test
  @DisplayName("ABA 방지: 좌석의 예매 번호가 이벤트와 다르면 반환하지 않는다")
  void execute_skip_when_booking_number_mismatch() {
    // given: 좌석 id 재사용으로 다른 예매가 재선점(SOLD)한 좌석을 과거 환불 이벤트가 반환하려는 상황
    Seat seat = seatWithStatus(SeatStatus.SOLD);
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.of(seat));

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, "BOOK-9999");

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.SOLD);
    verifyNoInteractions(seatStatusEventPublisher);
  }

  @Test
  @DisplayName("멱등: 이미 AVAILABLE 좌석이면 스킵한다")
  void execute_skip_when_already_available() {
    // given
    Seat seat = seatWithStatus(SeatStatus.AVAILABLE);
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.of(seat));

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, BOOKING_NUMBER);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verifyNoInteractions(seatStatusEventPublisher);
  }

  @Test
  @DisplayName("안전: HOLD(진행 중 선점) 좌석은 환불 대상이 아니므로 반환하지 않는다")
  void execute_skip_when_hold() {
    // given: 좌석 id 재사용으로 다른 예매가 선점 중일 수 있어, 환불이 활성 HOLD를 깨지 않아야 한다
    Seat seat = seatWithStatus(SeatStatus.HOLD);
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.of(seat));

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, BOOKING_NUMBER);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.HOLD);
    verifyNoInteractions(seatStatusEventPublisher);
  }

  @Test
  @DisplayName("멱등: 좌석이 없으면 예외 없이 스킵한다")
  void execute_skip_when_seat_not_found() {
    // given
    given(seatRepository.findById(SEAT_ID)).willReturn(Optional.empty());

    // when
    seatReleaseSoldSeatUseCase.execute(SEAT_ID, BOOKING_NUMBER);

    // then
    verifyNoInteractions(seatStatusEventPublisher);
  }
}
