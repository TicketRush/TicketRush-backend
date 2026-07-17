package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.out.repository.BookingSeatStatusReader;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.types.SeatStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingValidateSeatAvailableUseCase {

  private final BookingSeatStatusReader bookingSeatStatusReader;

  public void execute(Long seatId, Long performanceId) {
    SeatStatus seatStatus =
        bookingSeatStatusReader
            .findSeatStatus(seatId, performanceId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.SEAT_NOT_FOUND));

    if (seatStatus == SeatStatus.HOLD) {
      throw new BusinessException(ErrorStatus.SEAT_ALREADY_LOCKED);
    }

    if (seatStatus != SeatStatus.AVAILABLE) {
      throw new BusinessException(ErrorStatus.SEAT_NOT_AVAILABLE);
    }
  }
}
