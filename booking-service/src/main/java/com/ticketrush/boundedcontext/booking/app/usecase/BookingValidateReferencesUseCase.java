package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.out.repository.BookingReferenceReader;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingValidateReferencesUseCase {

  private final BookingReferenceReader bookingReferenceReader;

  public void execute(Long userId, Long performanceId, Long seatId) {
    if (!bookingReferenceReader.existsUserById(userId)) {
      throw new BusinessException(ErrorStatus.USER_NOT_FOUND);
    }

    if (!bookingReferenceReader.existsPerformanceById(performanceId)) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND);
    }

    if (!bookingReferenceReader.existsSeatByIdAndPerformanceId(seatId, performanceId)) {
      throw new BusinessException(ErrorStatus.SEAT_NOT_FOUND);
    }
  }
}
