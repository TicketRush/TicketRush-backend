package com.ticketrush.boundedcontext.booking.app.usecase;

import static com.ticketrush.global.status.ErrorStatus.BOOKING_EXPIRED;
import static com.ticketrush.global.status.ErrorStatus.BOOKING_NOT_FOUND;
import static com.ticketrush.global.status.ErrorStatus.BOOKING_SEAT_MISMATCH;
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

  private static final Long SEAT_ID = 3L;
  private static final Long PAID_AMOUNT = 150000L;

  @Test
  @DisplayName("성공: 예매를 확정하고 확정 시각을 기록하며 예매 번호를 반환한다")
  void execute_success() {
    // given
    Long bookingId = 1L;
    LocalDateTime confirmedAt = LocalDateTime.of(2026, 5, 22, 10, 30);
    Booking booking =
        Booking.builder()
            .userId(1L)
            .performanceId(2L)
            .seatId(SEAT_ID)
            .bookingNumber("BOOK-1234")
            .bookingStatus(BookingStatus.PENDING)
            .build();
    given(bookingRepository.findById(bookingId)).willReturn(Optional.of(booking));

    // when
    String bookingNumber =
        bookingConfirmUseCase.execute(bookingId, confirmedAt, SEAT_ID, PAID_AMOUNT);

    // then
    assertThat(bookingNumber).isEqualTo("BOOK-1234");
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
            .seatId(SEAT_ID)
            .bookingNumber("BOOK-1234")
            .bookingStatus(BookingStatus.CONFIRMED)
            .build();
    given(bookingRepository.findById(bookingId)).willReturn(Optional.of(booking));

    // when
    String bookingNumber =
        bookingConfirmUseCase.execute(bookingId, confirmedAt, SEAT_ID, PAID_AMOUNT);

    // then
    assertThat(bookingNumber).isEqualTo("BOOK-1234");
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
    assertThatThrownBy(
            () ->
                bookingConfirmUseCase.execute(bookingId, LocalDateTime.now(), SEAT_ID, PAID_AMOUNT))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(BOOKING_NOT_FOUND);
  }

  @Test
  @DisplayName("실패: 예매의 seatId와 결제 컨텍스트의 seatId가 다르면 확정하지 않고 예외를 던진다")
  void execute_fail_when_seat_mismatch() {
    // given
    Long bookingId = 1L;
    Long mismatchedSeatId = 999L;
    Booking booking =
        Booking.builder()
            .userId(1L)
            .performanceId(2L)
            .seatId(SEAT_ID)
            .bookingNumber("BOOK-1234")
            .bookingStatus(BookingStatus.PENDING)
            .build();
    given(bookingRepository.findById(bookingId)).willReturn(Optional.of(booking));

    // when & then
    assertThatThrownBy(
            () ->
                bookingConfirmUseCase.execute(
                    bookingId, LocalDateTime.now(), mismatchedSeatId, PAID_AMOUNT))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(BOOKING_SEAT_MISMATCH);

    // 검증 실패 시 예매는 확정되지 않는다.
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.PENDING);
  }

  @Test
  @DisplayName("실패: 만료된 예매는 확정할 수 없다")
  void execute_fail_when_booking_expired() {
    // given
    Long bookingId = 1L;
    Booking booking =
        Booking.builder()
            .userId(1L)
            .performanceId(2L)
            .seatId(SEAT_ID)
            .bookingNumber("BOOK-1234")
            .bookingStatus(BookingStatus.EXPIRED)
            .build();
    given(bookingRepository.findById(bookingId)).willReturn(Optional.of(booking));

    // when & then
    assertThatThrownBy(
            () ->
                bookingConfirmUseCase.execute(bookingId, LocalDateTime.now(), SEAT_ID, PAID_AMOUNT))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(BOOKING_EXPIRED);
  }
}
