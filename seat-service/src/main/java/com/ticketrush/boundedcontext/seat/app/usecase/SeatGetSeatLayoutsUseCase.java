package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutResponse;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SeatGetSeatLayoutsUseCase {

  private final SeatRepository seatRepository;

  public List<SeatLayoutResponse> execute(Long performanceId) {
    // 공연 ID에 해당하는 정적 좌석 맵 리스트 반환
    return seatRepository.findSeatLayoutsByPerformanceId(performanceId);
  }
}
