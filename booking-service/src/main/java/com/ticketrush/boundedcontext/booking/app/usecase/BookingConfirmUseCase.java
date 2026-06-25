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

  public String execute(Long bookingId, LocalDateTime confirmedAt, Long expectedSeatId) {
    Booking booking =
        bookingRepository
            .findById(bookingId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    // 결제 컨텍스트의 seatId가 예매의 seatId와 일치하는지 검증한다.
    // 불일치 시 좌석 SOLD 확정으로 이어지지 않도록 예매 확정 전에 차단한다.
    if (!booking.getSeatId().equals(expectedSeatId)) {
      throw new BusinessException(ErrorStatus.BOOKING_SEAT_MISMATCH);
    }

    booking.confirm(confirmedAt);

    return booking.getBookingNumber();
  }
}
