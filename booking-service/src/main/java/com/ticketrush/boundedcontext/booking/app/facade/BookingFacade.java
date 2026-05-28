package com.ticketrush.boundedcontext.booking.app.facade;

import com.ticketrush.boundedcontext.booking.app.dto.request.BookingCreateRequest;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingCountResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPendingResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCancelMyBookingUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCountUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCreateUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetMyBookingsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingIssueNumberUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateReferencesUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateSeatAvailableUseCase;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingFacade {

  private final BookingIssueNumberUseCase bookingIssueNumberUseCase;
  private final BookingCreateUseCase bookingCreateUseCase;
  private final BookingGetMyBookingsUseCase bookingGetMyBookingsUseCase;
  private final BookingCountUseCase bookingCountUseCase;
  private final BookingCancelMyBookingUseCase bookingCancelMyBookingUseCase;
  private final BookingValidateReferencesUseCase bookingValidateReferencesUseCase;
  private final BookingValidateSeatAvailableUseCase bookingValidateSeatAvailableUseCase;

  public BookingPendingResponse createBooking(Long userId, Long performanceId, Long seatId) {
    // 참조 및 좌석 가용성 검증 실행
    bookingValidateReferencesUseCase.execute(userId, performanceId, seatId);
    bookingValidateSeatAvailableUseCase.execute(seatId, performanceId);

    // 고유 예약 번호 발급
    String bookingNumber = bookingIssueNumberUseCase.execute();

    // PENDING 상태로 예매 생성
    BookingCreateRequest request =
        new BookingCreateRequest(userId, performanceId, seatId, bookingNumber);

    Booking booking = bookingCreateUseCase.execute(request);

    return BookingPendingResponse.from(booking);
  }

  public Page<BookingSummaryResponse> getMyBookings(
      Long userId, BookingStatus bookingStatus, OffsetPageRequest pageRequest) {
    return bookingGetMyBookingsUseCase.execute(userId, bookingStatus, pageRequest);
  }

  public BookingCountResponse countMyBookings(Long userId, BookingStatus bookingStatus) {
    return bookingCountUseCase.execute(userId, bookingStatus);
  }

  public void cancelMyBooking(Long userId, String bookingNumber) {
    bookingCancelMyBookingUseCase.execute(userId, bookingNumber);
  }
}
