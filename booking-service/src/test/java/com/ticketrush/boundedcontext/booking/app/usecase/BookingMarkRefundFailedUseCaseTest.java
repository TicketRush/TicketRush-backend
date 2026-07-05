package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingMarkRefundFailedUseCaseTest {

  @InjectMocks private BookingMarkRefundFailedUseCase bookingMarkRefundFailedUseCase;

  @Mock private BookingRepository bookingRepository;

  private static final Long BOOKING_ID = 1L;

  private Booking bookingWithStatus(BookingStatus status) {
    return Booking.builder()
        .bookingNumber("BOOK-1234")
        .userId(10L)
        .performanceId(2L)
        .seatId(3L)
        .bookingStatus(status)
        .build();
  }

  @Test
  @DisplayName("성공: REFUNDING 예매를 REFUND_FAILED로 보상한다")
  void execute_marks_refunding_as_refund_failed() {
    // given
    Booking booking = bookingWithStatus(BookingStatus.REFUNDING);
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when
    bookingMarkRefundFailedUseCase.execute(BOOKING_ID);

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUND_FAILED);
  }

  @Test
  @DisplayName("멱등: 이미 REFUND_FAILED면 상태를 유지한다")
  void execute_is_idempotent_when_already_refund_failed() {
    // given
    Booking booking = bookingWithStatus(BookingStatus.REFUND_FAILED);
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when
    bookingMarkRefundFailedUseCase.execute(BOOKING_ID);

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUND_FAILED);
  }

  @Test
  @DisplayName("교차 경로: 이미 REFUNDED로 종결된 예매는 전이하지 않는다(예외 없이)")
  void execute_no_op_when_already_refunded() {
    // given: 환불 성공 이벤트가 먼저 도착해 이미 종결된 경우엔 실패 보상으로 되돌리지 않는다
    Booking booking = bookingWithStatus(BookingStatus.REFUNDED);
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when
    bookingMarkRefundFailedUseCase.execute(BOOKING_ID);

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUNDED);
  }

  @Test
  @DisplayName("스킵: 예매 내역이 없으면 예외 없이 종료된다")
  void execute_skip_when_booking_not_found() {
    // given
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.empty());

    // when
    bookingMarkRefundFailedUseCase.execute(BOOKING_ID);

    // then
    verify(bookingRepository).findById(BOOKING_ID);
    verifyNoMoreInteractions(bookingRepository);
  }
}
