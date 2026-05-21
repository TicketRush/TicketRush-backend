package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsResponse;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SeatGetStatusCountsUseCase {

  private final SeatRepository seatRepository;

  public SeatStatusCountsResponse execute(Long performanceId) {
    return seatRepository.getStatusCountsByPerformanceId(performanceId, LocalDateTime.now());
  }
}
