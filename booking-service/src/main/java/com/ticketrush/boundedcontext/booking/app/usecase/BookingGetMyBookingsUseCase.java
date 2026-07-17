package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingGetMyBookingsUseCase {

  private final BookingRepository bookingRepository;

  public Page<BookingSummaryResponse> execute(
      Long userId, BookingStatus bookingStatus, OffsetPageRequest pageRequest) {
    return bookingRepository
        .findByUserIdAndBookingStatus(
            userId,
            bookingStatus,
            PageRequest.of(pageRequest.page(), pageRequest.size(), sortByStatus(bookingStatus)))
        .map(BookingSummaryResponse::from);
  }

  private Sort sortByStatus(BookingStatus bookingStatus) {
    if (bookingStatus == BookingStatus.CONFIRMED) {
      return Sort.by(Sort.Order.desc("confirmedAt"), Sort.Order.desc("createdAt"));
    }

    return Sort.by(Sort.Order.desc("createdAt"));
  }
}
