package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class BookingConfirmUseCase {

  private final BookingRepository bookingRepository;

  /**
   * 결제 완료 이벤트로 예매를 확정한다.
   *
   * <p>{@code paidAmount}는 그 이벤트가 싣고 온 실제 결제 금액이며 예매에 그대로 기록된다 (#561). 관리자 매출 집계가 공연 가격을 되묻지 않아도
   * 되도록 하는 값이라, 확정 경로에서 빠뜨리면 그 예매는 매출에서 영영 누락된다.
   */
  public String execute(
      Long bookingId, LocalDateTime confirmedAt, Long expectedSeatId, Long paidAmount) {
    Booking booking =
        bookingRepository
            .findById(bookingId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    // 결제 컨텍스트의 seatId가 예매의 seatId와 일치하는지 검증한다.
    // 불일치 시 좌석 SOLD 확정으로 이어지지 않도록 예매 확정 전에 차단한다.
    if (!booking.getSeatId().equals(expectedSeatId)) {
      throw new BusinessException(ErrorStatus.BOOKING_SEAT_MISMATCH);
    }

    booking.confirm(confirmedAt, paidAmount);

    return booking.getBookingNumber();
  }
}
