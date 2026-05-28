package com.ticketrush.boundedcontext.seat.app.usecase;

import static com.ticketrush.global.status.ErrorStatus.SEAT_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.types.SeatStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatReleaseBookedSeatUseCaseTest {

  @InjectMocks private SeatReleaseBookedSeatUseCase seatReleaseBookedSeatUseCase;

  @Mock private SeatRepository seatRepository;
  @Mock private SeatStatusEventPublisher seatStatusEventPublisher;

  @Test
  @DisplayName("성공: SOLD 좌석을 AVAILABLE로 반환하고 상태 변경 이벤트를 발행한다")
  void execute_success_when_sold() {
    // given
    Long seatId = 1L;
    String bookingNumber = "BOOK-1234";
    Seat seat =
        Seat.builder()
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.SOLD)
            .bookingNumber(bookingNumber)
            .build();
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when
    seatReleaseBookedSeatUseCase.execute(seatId, bookingNumber);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verify(seatStatusEventPublisher).publishAfterCommit(seat);
  }

  @Test
  @DisplayName("성공: 이벤트의 예매 번호가 없으면 안전하게 스킵한다")
  void execute_skip_when_event_booking_number_is_blank() {
    // when
    seatReleaseBookedSeatUseCase.execute(1L, " ");

    // then
    verifyNoInteractions(seatRepository, seatStatusEventPublisher);
  }

  @Test
  @DisplayName("성공: 이미 AVAILABLE 좌석이면 멱등하게 스킵한다")
  void execute_skip_when_already_available() {
    // given
    Long seatId = 1L;
    String bookingNumber = "BOOK-1234";
    Seat seat =
        Seat.builder().performanceId(1L).seatNumber("A-1").seatStatus(SeatStatus.AVAILABLE).build();
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when
    seatReleaseBookedSeatUseCase.execute(seatId, bookingNumber);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verifyNoInteractions(seatStatusEventPublisher);
  }

  @Test
  @DisplayName("성공: 좌석의 예매 번호가 없으면 안전하게 스킵한다")
  void execute_skip_when_seat_booking_number_is_null() {
    // given
    Long seatId = 1L;
    Seat seat =
        Seat.builder().performanceId(1L).seatNumber("A-1").seatStatus(SeatStatus.SOLD).build();
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when
    seatReleaseBookedSeatUseCase.execute(seatId, "BOOK-1234");

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.SOLD);
    verifyNoInteractions(seatStatusEventPublisher);
  }

  @Test
  @DisplayName("성공: 이벤트의 예매 번호와 좌석의 예매 번호가 다르면 스킵한다")
  void execute_skip_when_booking_number_mismatched() {
    // given
    Long seatId = 1L;
    Seat seat =
        Seat.builder()
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.SOLD)
            .bookingNumber("BOOK-OTHER")
            .build();
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when
    seatReleaseBookedSeatUseCase.execute(seatId, "BOOK-1234");

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.SOLD);
    verifyNoInteractions(seatStatusEventPublisher);
  }

  @Test
  @DisplayName("실패: 좌석이 없으면 SEAT_NOT_FOUND를 던진다")
  void execute_fail_when_seat_not_found() {
    // given
    Long seatId = 1L;
    given(seatRepository.findById(seatId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> seatReleaseBookedSeatUseCase.execute(seatId, "BOOK-1234"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(SEAT_NOT_FOUND);

    verifyNoInteractions(seatStatusEventPublisher);
  }
}
