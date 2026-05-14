package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAvailabilityResponse;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SeatGetAvailabilityUseCase {

  private final SeatRepository seatRepository;

  public SeatAvailabilityResponse execute(Long performanceId) {
    return seatRepository.countSeatAvailabilityByPerformanceId(performanceId, SeatStatus.AVAILABLE);
  }
}
