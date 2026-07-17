package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingCancelUseCaseTest {

  @InjectMocks private BookingCancelUseCase bookingCancelUseCase;

  @Mock private BookingRepository bookingRepository;

  @Test
  @DisplayName("성공: 예매 내역이 존재하면 cancel()이 호출된다")
  void execute_success() {
    // given
    Long bookingId = 1L;
    Booking mockBooking = mock(Booking.class);
    given(bookingRepository.findById(bookingId)).willReturn(Optional.of(mockBooking));

    // when
    bookingCancelUseCase.execute(bookingId);

    // then
    verify(mockBooking).cancelPendingPayment();
  }

  @Test
  @DisplayName("스킵: 예매 내역이 존재하지 않으면 예외 없이 종료된다")
  void execute_skip_when_booking_not_found() {
    // given
    Long bookingId = 1L;
    given(bookingRepository.findById(bookingId)).willReturn(Optional.empty());

    // when
    bookingCancelUseCase.execute(bookingId);

    // then
    verify(bookingRepository).findById(bookingId);
    verifyNoMoreInteractions(bookingRepository);
  }
}
