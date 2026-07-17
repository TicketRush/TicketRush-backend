package com.ticketrush.boundedcontext.booking.app.usecase;

import static com.ticketrush.global.status.ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED;
import static com.ticketrush.global.status.ErrorStatus.BOOKING_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.shared.booking.event.RefundRequestedEvent;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingCancelMyBookingUseCaseTest {

  @InjectMocks private BookingCancelMyBookingUseCase bookingCancelMyBookingUseCase;

  @Mock private BookingRepository bookingRepository;
  @Mock private EventPublisher eventPublisher;

  @Test
  @DisplayName("성공: 본인의 확정 예매를 REFUNDING으로 전환하고 RefundRequestedEvent를 발행한다")
  void execute_success() {
    // given
    Long userId = 1L;
    String bookingNumber = "BOOK-1234";
    Booking booking =
        Booking.builder()
            .userId(userId)
            .performanceId(2L)
            .seatId(3L)
            .bookingNumber(bookingNumber)
            .bookingStatus(BookingStatus.CONFIRMED)
            .build();

    given(bookingRepository.findByBookingNumberAndUserId(bookingNumber, userId))
        .willReturn(Optional.of(booking));

    // when
    bookingCancelMyBookingUseCase.execute(userId, bookingNumber);

    // then: 곧바로 종결하지 않고 환불을 요청한다(refund-first). 좌석 반환·종결은 환불 성공 이벤트에 매단다.
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUNDING);
    verify(eventPublisher)
        .publish(
            argThat(
                event ->
                    event instanceof RefundRequestedEvent refundRequested
                        && refundRequested.bookingNumber().equals(bookingNumber)
                        && refundRequested.seatId().equals(3L)
                        && refundRequested.userId().equals(userId)
                        && refundRequested.requestedAt() != null));
  }

  @Test
  @DisplayName("성공: 환불에 실패해 복원된 예매도 다시 취소해 재환불을 요청할 수 있다")
  void execute_retries_refund_when_previous_refund_failed() {
    // given: 환불 실패로 CONFIRMED로 복원된 예매(refundFailedAt이 찍혀 있다)
    Long userId = 1L;
    String bookingNumber = "BOOK-1234";
    Booking booking =
        Booking.builder()
            .userId(userId)
            .performanceId(2L)
            .seatId(3L)
            .bookingNumber(bookingNumber)
            .bookingStatus(BookingStatus.REFUNDING)
            .build();
    booking.recordRefundFailure(LocalDateTime.of(2026, 7, 10, 12, 0));

    given(bookingRepository.findByBookingNumberAndUserId(bookingNumber, userId))
        .willReturn(Optional.of(booking));

    // when
    bookingCancelMyBookingUseCase.execute(userId, bookingNumber);

    // then: 흡수 상태가 없으므로 별도 전이 없이 기존 취소 경로가 곧 재환불이다 (#391)
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.REFUNDING);
    verify(eventPublisher).publish(any(RefundRequestedEvent.class));
  }

  @Test
  @DisplayName("실패: 본인의 예매가 아니거나 예매가 없으면 BOOKING_NOT_FOUND를 던진다")
  void execute_fail_when_booking_not_found_or_not_owned() {
    // given
    Long userId = 1L;
    String bookingNumber = "BOOK-1234";
    given(bookingRepository.findByBookingNumberAndUserId(bookingNumber, userId))
        .willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> bookingCancelMyBookingUseCase.execute(userId, bookingNumber))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(BOOKING_NOT_FOUND);

    verifyNoInteractions(eventPublisher);
  }

  @Test
  @DisplayName("실패: 확정 상태가 아닌 예매는 취소할 수 없다")
  void execute_fail_when_booking_status_is_not_confirmed() {
    // given
    Long userId = 1L;
    String bookingNumber = "BOOK-1234";
    Booking booking =
        Booking.builder()
            .userId(userId)
            .performanceId(2L)
            .seatId(3L)
            .bookingNumber(bookingNumber)
            .bookingStatus(BookingStatus.PENDING)
            .build();

    given(bookingRepository.findByBookingNumberAndUserId(bookingNumber, userId))
        .willReturn(Optional.of(booking));

    // when & then
    assertThatThrownBy(() -> bookingCancelMyBookingUseCase.execute(userId, bookingNumber))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorStatus())
        .isEqualTo(BOOKING_CANCEL_NOT_ALLOWED);

    verifyNoInteractions(eventPublisher);
  }
}
