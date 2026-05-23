package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatNumberResponse;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    Map<Long, SeatNumberResponse> seatNumbersById =
        seatRepository.findSeatNumbersByIdIn(seatIds).stream()
            .collect(Collectors.toMap(SeatNumberResponse::seatId, Function.identity()));

    return seatIds.stream().map(seatNumbersById::get).filter(Objects::nonNull).toList();
  }
}
