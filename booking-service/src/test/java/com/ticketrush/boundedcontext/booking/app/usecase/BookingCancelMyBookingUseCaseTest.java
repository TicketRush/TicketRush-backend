package com.ticketrush.boundedcontext.booking.app.usecase;

import static com.ticketrush.global.status.ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED;
import static com.ticketrush.global.status.ErrorStatus.BOOKING_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.shared.booking.event.BookingCanceledEvent;
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
  @DisplayName("성공: 본인의 확정 예매를 취소하고 BookingCanceledEvent를 발행한다")
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

    // then
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.CANCELED);
    verify(eventPublisher)
        .publish(
            argThat(
                event ->
                    event instanceof BookingCanceledEvent canceledEvent
                        && canceledEvent.bookingNumber().equals(bookingNumber)
                        && canceledEvent.seatId().equals(3L)
                        && canceledEvent.userId().equals(userId)
                        && canceledEvent.canceledAt() != null));
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
