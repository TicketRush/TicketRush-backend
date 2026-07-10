package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingMarkRefundedUseCaseTest {

  @InjectMocks private BookingMarkRefundedUseCase bookingMarkRefundedUseCase;

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
  @DisplayName("성공: CONFIRMED 예매를 REFUNDED로 종결한다")
  void execute_marks_confirmed_as_refunded() {
    // given
    Booking booking = bookingWithStatus(BookingStatus.CONFIRMED);
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when
    bookingMarkRefundedUseCase.execute(BOOKING_ID);

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUNDED);
  }

  @Test
  @DisplayName("성공: REFUNDING 예매를 REFUNDED로 종결한다")
  void execute_marks_refunding_as_refunded() {
    // given: 후속(#91) 환불-선행 흐름에서 booking이 REFUNDING을 거친 경우
    Booking booking = bookingWithStatus(BookingStatus.REFUNDING);
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when
    bookingMarkRefundedUseCase.execute(BOOKING_ID);

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUNDED);
  }

  @Test
  @DisplayName("정합 회복: 환불에 실패해 복원된 예매도 우회 환불이 성공하면 REFUNDED로 수렴한다")
  void execute_converges_when_refund_previously_failed() {
    // given: 환불 실패로 CONFIRMED로 복원된 뒤, 결제 취소 API로 우회 환불이 성공해 PaymentCanceledEvent가 도착한 경우
    Booking booking = bookingWithStatus(BookingStatus.REFUNDING);
    booking.recordRefundFailure(LocalDateTime.of(2026, 7, 10, 12, 0));
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when
    bookingMarkRefundedUseCase.execute(BOOKING_ID);

    // then: 실패를 상태로 두지 않으므로 별도 전이 없이 정합이 회복된다 (#391)
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUNDED);
  }

  @Test
  @DisplayName("멱등: 이미 REFUNDED면 상태를 유지한다")
  void execute_is_idempotent_when_already_refunded() {
    // given
    Booking booking = bookingWithStatus(BookingStatus.REFUNDED);
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when
    bookingMarkRefundedUseCase.execute(BOOKING_ID);

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUNDED);
  }

  @Test
  @DisplayName("교차 경로: 이미 CANCELED된 예매는 전이하지 않는다(예외 없이)")
  void execute_no_op_when_canceled() {
    // given
    Booking booking = bookingWithStatus(BookingStatus.CANCELED);
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when
    bookingMarkRefundedUseCase.execute(BOOKING_ID);

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CANCELED);
  }

  @Test
  @DisplayName("스킵: 예매 내역이 없으면 예외 없이 종료된다")
  void execute_skip_when_booking_not_found() {
    // given
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.empty());

    // when
    bookingMarkRefundedUseCase.execute(BOOKING_ID);

    // then
    verify(bookingRepository).findById(BOOKING_ID);
    verifyNoMoreInteractions(bookingRepository);
  }
}
