package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.out.repository.BookingSeatStatusReader;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingValidateSeatAvailableUseCase {

  private static final String AVAILABLE_STATUS = "AVAILABLE";
  private static final String HOLD_STATUS = "HOLD";

  private final BookingSeatStatusReader bookingSeatStatusReader;

  public void execute(Long seatId, Long performanceId) {
    String seatStatus = bookingSeatStatusReader.findSeatStatus(seatId, performanceId);

    if (Objects.equals(seatStatus, HOLD_STATUS)) {
      throw new BusinessException(ErrorStatus.SEAT_ALREADY_LOCKED);
    }

    if (!Objects.equals(seatStatus, AVAILABLE_STATUS)) {
      throw new BusinessException(ErrorStatus.SEAT_NOT_AVAILABLE);
    }
  }
}
