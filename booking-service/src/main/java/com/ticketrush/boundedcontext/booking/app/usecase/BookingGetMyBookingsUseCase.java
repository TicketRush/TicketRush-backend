package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingGetMyBookingsUseCase {

  private final BookingRepository bookingRepository;

  public List<BookingSummaryResponse> execute(Long userId, BookingStatus bookingStatus) {
    return bookingRepository
        .findByUserIdAndBookingStatusOrderByConfirmedAtDescCreatedAtDesc(userId, bookingStatus)
        .stream()
        .map(BookingSummaryResponse::from)
        .toList();
  }
}
