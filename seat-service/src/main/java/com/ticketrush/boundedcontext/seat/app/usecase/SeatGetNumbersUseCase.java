package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatNumberResponse;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SeatGetNumbersUseCase {

  private final SeatRepository seatRepository;

  public List<SeatNumberResponse> execute(List<Long> seatIds) {
    if (seatIds.isEmpty()) {
      return List.of();
    }

    return seatRepository.findSeatNumbersByIdIn(seatIds);
  }
}
