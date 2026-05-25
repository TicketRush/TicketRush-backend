package com.ticketrush.boundedcontext.booking.app.usecase;

import static com.ticketrush.global.status.ErrorStatus.BOOKING_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingConfirmUseCaseTest {

  @InjectMocks private BookingConfirmUseCase bookingConfirmUseCase;

  @Mock private BookingRepository bookingRepository;

  @Test
  @DisplayName("성공: 예매를 확정하고 확정 시각을 기록한다")
  void execute_success() {
    // given
    Long bookingId = 1L;
    LocalDateTime confirmedAt = LocalDateTime.of(2026, 5, 22, 10, 30);
    Booking booking =
        Booking.builder()
            .userId(1L)
            .performanceId(2L)
            .seatId(3L)
            .bookingNumber("BOOK-1234")
            .bookingStatus(BookingStatus.PENDING)
            .build();
    given(bookingRepository.findById(bookingId)).willReturn(Optional.of(booking));

    // when
    bookingConfirmUseCase.execute(bookingId, confirmedAt);

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(booking.getConfirmedAt()).isEqualTo(confirmedAt);
  }

  @Test
  @DisplayName("성공: 이미 확정된 예매의 확정 시각이 비어 있으면 보정한다")
  void execute_fills_confirmed_at_when_already_confirmed_without_timestamp() {
    // given
    Long bookingId = 1L;
    LocalDateTime confirmedAt = LocalDateTime.of(2026, 5, 22, 10, 30);
    Booking booking =
        Booking.builder()
            .userId(1L)
            .performanceId(2L)
            .seatId(3L)
            .bookingNumber("BOOK-1234")
            .bookingStatus(BookingStatus.CONFIRMED)
            .build();
    given(bookingRepository.findById(bookingId)).willReturn(Optional.of(booking));

    // when
    bookingConfirmUseCase.execute(bookingId, confirmedAt);

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(booking.getConfirmedAt()).isEqualTo(confirmedAt);
  }

  @Test
  @DisplayName("실패: 예매가 없으면 예외를 던진다")
  void execute_fail_when_booking_not_found() {
    // given
    Long bookingId = 1L;
    given(bookingRepository.findById(bookingId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> bookingConfirmUseCase.execute(bookingId, LocalDateTime.now()))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(BOOKING_NOT_FOUND);
  }
}
