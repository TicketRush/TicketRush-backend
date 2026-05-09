package com.ticketrush.boundedcontext.booking.app.usecase;

import static com.ticketrush.global.status.ErrorStatus.SEAT_ALREADY_LOCKED;
import static com.ticketrush.global.status.ErrorStatus.SEAT_NOT_AVAILABLE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.out.repository.BookingSeatStatusReader;
import com.ticketrush.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingValidateSeatAvailableUseCaseTest {

  @InjectMocks private BookingValidateSeatAvailableUseCase bookingValidateSeatAvailableUseCase;

  @Mock private BookingSeatStatusReader bookingSeatStatusReader;

  @Test
  @DisplayName("성공: 좌석이 AVAILABLE 상태이면 검증을 통과한다")
  void execute_success() {
    // given
    Long seatId = 1L;
    Long performanceId = 2L;

    given(bookingSeatStatusReader.findSeatStatus(seatId, performanceId)).willReturn("AVAILABLE");

    // when
    bookingValidateSeatAvailableUseCase.execute(seatId, performanceId);

    // then
    verify(bookingSeatStatusReader).findSeatStatus(seatId, performanceId);
  }

  @Test
  @DisplayName("실패: 좌석이 HOLD 상태이면 SEAT_ALREADY_LOCKED 예외가 발생한다")
  void execute_fail_when_seat_is_held() {
    // given
    Long seatId = 1L;
    Long performanceId = 2L;

    given(bookingSeatStatusReader.findSeatStatus(seatId, performanceId)).willReturn("HOLD");

    // when & then
    assertThatThrownBy(() -> bookingValidateSeatAvailableUseCase.execute(seatId, performanceId))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(SEAT_ALREADY_LOCKED);

    verify(bookingSeatStatusReader).findSeatStatus(seatId, performanceId);
  }

  @Test
  @DisplayName("실패: 좌석이 AVAILABLE 상태가 아니면 SEAT_NOT_AVAILABLE 예외가 발생한다")
  void execute_fail_when_seat_is_not_available() {
    // given
    Long seatId = 1L;
    Long performanceId = 2L;

    given(bookingSeatStatusReader.findSeatStatus(seatId, performanceId)).willReturn("SOLD");

    // when & then
    assertThatThrownBy(() -> bookingValidateSeatAvailableUseCase.execute(seatId, performanceId))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(SEAT_NOT_AVAILABLE);

    verify(bookingSeatStatusReader).findSeatStatus(seatId, performanceId);
  }
}
