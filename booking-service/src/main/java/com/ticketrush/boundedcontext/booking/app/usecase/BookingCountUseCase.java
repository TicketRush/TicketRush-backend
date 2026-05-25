package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingCountResponse;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingCountUseCase {

  private final BookingRepository bookingRepository;

  public BookingCountResponse execute(Long userId, BookingStatus bookingStatus) {
    long count = bookingRepository.countByUserIdAndBookingStatus(userId, bookingStatus);
    return new BookingCountResponse(bookingStatus, count);
  }
}
