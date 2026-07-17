package com.ticketrush.boundedcontext.booking.app.facade;

import com.ticketrush.boundedcontext.booking.app.dto.request.BookingCreateRequest;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingCountResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPendingResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingAdminRetryRefundUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCancelMyBookingUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCountUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingCreateUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetMyBookingsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetRefundFailedBookingsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetRefundingStuckBookingsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingIssueNumberUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateReferencesUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateSeatAvailableUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingValidateTicketNotUsedUseCase;
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
  private final BookingValidateTicketNotUsedUseCase bookingValidateTicketNotUsedUseCase;
  private final BookingGetRefundFailedBookingsUseCase bookingGetRefundFailedBookingsUseCase;
  private final BookingGetRefundingStuckBookingsUseCase bookingGetRefundingStuckBookingsUseCase;
  private final BookingAdminRetryRefundUseCase bookingAdminRetryRefundUseCase;

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
    // 입장 완료 예매의 환불 차단 (#399). 소유권을 함께 검증해 비소유자에게 타인 예매의 입장 여부가 새지 않게 한다.
    bookingValidateTicketNotUsedUseCase.execute(userId, bookingNumber);

    bookingCancelMyBookingUseCase.execute(userId, bookingNumber);
  }

  public Page<BookingSummaryResponse> getRefundFailedBookings(OffsetPageRequest pageRequest) {
    return bookingGetRefundFailedBookingsUseCase.execute(pageRequest);
  }

  public Page<BookingSummaryResponse> getRefundingStuckBookings(OffsetPageRequest pageRequest) {
    return bookingGetRefundingStuckBookingsUseCase.execute(pageRequest);
  }

  public void retryRefund(Long adminId, String bookingNumber) {
    // 관리자 재환불에도 같은 정책을 적용한다 (#399). 사용자만 막고 CS 도구로 우회되면 좌석은 여전히 반환된다.
    // 다만 차단 대상은 CONFIRMED에서의 환불 개시뿐이다 — REFUNDING 고착 재발행(#397)은 통과시킨다.
    // 막으면 REFUNDING을 빠져나올 유일한 수단이 사라져 흡수 상태가 되살아난다(가드 Javadoc 참고).
    bookingValidateTicketNotUsedUseCase.executeForAdmin(bookingNumber);

    bookingAdminRetryRefundUseCase.execute(adminId, bookingNumber);
  }
}
