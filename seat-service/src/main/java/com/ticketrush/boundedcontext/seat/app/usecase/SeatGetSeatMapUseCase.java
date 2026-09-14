package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatMapResponse;
import com.ticketrush.boundedcontext.seat.out.repository.SeatLayoutRepository;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SeatGetSeatMapUseCase {

  private final SeatRepository seatRepository;
  private final SeatLayoutRepository seatLayoutRepository;

  /** 배치 크기는 공연당 한 번, 좌석은 좌표를 포함해 조회한다(#645). 배치도가 없으면 layout은 null이다. */
  public SeatMapResponse execute(Long performanceId) {
    return new SeatMapResponse(
        seatLayoutRepository.findSizeByPerformanceId(performanceId).orElse(null),
        seatRepository.findSeatMapByPerformanceId(performanceId));
  }
}
