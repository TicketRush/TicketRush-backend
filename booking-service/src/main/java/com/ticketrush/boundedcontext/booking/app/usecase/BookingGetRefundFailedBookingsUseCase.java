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

/**
 * 환불에 실패해 아직 해결되지 않은 예매를 관리자가 조회한다 (#391).
 *
 * <p>환불 실패는 상태가 아니라 CONFIRMED + {@code refundFailedAt} 조합으로 표현되므로, 두 조건을 함께 건다. REFUNDING(재환불 진행
 * 중)이나 REFUNDED(끝내 환불됨)는 더 이상 조치가 필요 없으므로 제외된다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingGetRefundFailedBookingsUseCase {

  private final BookingRepository bookingRepository;

  public Page<BookingSummaryResponse> execute(OffsetPageRequest pageRequest) {
    return bookingRepository
        .findByBookingStatusAndRefundFailedAtIsNotNull(
            BookingStatus.CONFIRMED,
            PageRequest.of(
                pageRequest.page(), pageRequest.size(), Sort.by(Sort.Order.desc("refundFailedAt"))))
        .map(BookingSummaryResponse::from);
  }
}
