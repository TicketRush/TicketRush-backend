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
class BookingRecordRefundFailureUseCaseTest {

  @InjectMocks private BookingRecordRefundFailureUseCase bookingRecordRefundFailureUseCase;

  @Mock private BookingRepository bookingRepository;

  private static final Long BOOKING_ID = 1L;
  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 7, 10, 12, 0);

  private Booking bookingWithStatus(BookingStatus status) {
    return Booking.builder()
        .bookingNumber("BOOK-1234")
        .userId(10L)
        .performanceId(2L)
        .seatId(3L)
        .bookingStatus(status)
        .build();
  }

  /** 환불에 한 번 실패해 CONFIRMED로 복원된 예매(refundFailedAt이 찍힌 상태). */
  private Booking bookingRestoredAfterRefundFailure() {
    Booking booking = bookingWithStatus(BookingStatus.REFUNDING);
    booking.recordRefundFailure(FAILED_AT);
    return booking;
  }

  @Test
  @DisplayName("성공: REFUNDING 예매를 CONFIRMED로 복원하고 실패 시각을 기록한다")
  void execute_restores_refunding_to_confirmed() {
    // given
    Booking booking = bookingWithStatus(BookingStatus.REFUNDING);
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when
    bookingRecordRefundFailureUseCase.execute(BOOKING_ID, FAILED_AT);

    // then: 환불이 실패했으므로 취소가 성사되지 않았다 → 예매는 유효한 CONFIRMED로 되돌아간다
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(booking.getRefundFailedAt()).isEqualTo(FAILED_AT);
  }

  @Test
  @DisplayName("멱등: 이미 복원된 예매에 이벤트가 재전달되면 최초 실패 시각을 유지한다")
  void execute_is_idempotent_when_already_restored() {
    // given
    Booking booking = bookingRestoredAfterRefundFailure();
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when
    bookingRecordRefundFailureUseCase.execute(BOOKING_ID, FAILED_AT.plusHours(1));

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(booking.getRefundFailedAt()).isEqualTo(FAILED_AT);
  }

  @Test
  @DisplayName("이벤트 재생: 진행 중인 재환불 시도보다 앞선 실패 이벤트는 그 시도를 중단시키지 않는다")
  void execute_ignores_stale_event_during_new_refund_attempt() {
    // given: 한 번 실패한 뒤 재환불을 요청해 다시 REFUNDING인 예매
    Booking booking = bookingRestoredAfterRefundFailure();
    booking.requestRefund();
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when: Inbox retention 만료 후 옛 RefundFailedEvent가 재생돼 도착한다
    bookingRecordRefundFailureUseCase.execute(BOOKING_ID, FAILED_AT);

    // then: 진행 중인 시도를 중단시키지 않고 stale 시각으로 덮어쓰지도 않는다
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUNDING);
    assertThat(booking.getRefundFailedAt()).isEqualTo(FAILED_AT);
  }

  @Test
  @DisplayName("성공: 재환불이 또 실패하면 최신 실패 시각으로 갱신하며 복원한다")
  void execute_restores_again_with_newer_failure() {
    // given: 재환불 진행 중인 예매
    Booking booking = bookingRestoredAfterRefundFailure();
    booking.requestRefund();
    LocalDateTime secondFailure = FAILED_AT.plusDays(1);
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when
    bookingRecordRefundFailureUseCase.execute(BOOKING_ID, secondFailure);

    // then: 반복 실패해도 흡수 상태가 되지 않는다
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(booking.getRefundFailedAt()).isEqualTo(secondFailure);
  }

  @Test
  @DisplayName("교차 경로: 이미 REFUNDED로 종결된 예매는 복원하지 않는다(예외 없이)")
  void execute_no_op_when_already_refunded() {
    // given: 환불 성공 이벤트가 먼저 도착해 이미 종결된 경우엔 실패 기록으로 되돌리지 않는다
    Booking booking = bookingWithStatus(BookingStatus.REFUNDED);
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when
    bookingRecordRefundFailureUseCase.execute(BOOKING_ID, FAILED_AT);

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUNDED);
    assertThat(booking.getRefundFailedAt()).isNull();
  }

  @Test
  @DisplayName("교차 경로: 환불을 요청한 적 없는 CONFIRMED 예매는 복원 대상이 아니다")
  void execute_no_op_when_confirmed_without_refund_failure() {
    // given: REFUNDING을 거치지 않은 CONFIRMED에 실패 이벤트가 오는 건 비정상 교차 경로다
    Booking booking = bookingWithStatus(BookingStatus.CONFIRMED);
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));

    // when
    bookingRecordRefundFailureUseCase.execute(BOOKING_ID, FAILED_AT);

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(booking.getRefundFailedAt()).isNull();
  }

  @Test
  @DisplayName("스킵: 예매 내역이 없으면 예외 없이 종료된다")
  void execute_skip_when_booking_not_found() {
    // given
    given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.empty());

    // when
    bookingRecordRefundFailureUseCase.execute(BOOKING_ID, FAILED_AT);

    // then
    verify(bookingRepository).findById(BOOKING_ID);
    verifyNoMoreInteractions(bookingRepository);
  }
}
