package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.domain.entity.SeatLayout;
import com.ticketrush.boundedcontext.seat.out.repository.SeatLayoutRepository;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatCreateDefaultLayoutUseCase {

  private static final int DEFAULT_TOTAL_ROWS = 10;
  private static final int DEFAULT_MAX_COLS = 12;

  private final SeatRepository seatRepository;
  private final SeatLayoutRepository seatLayoutRepository;

  @Transactional
  public void execute(Long performanceId) {
    if (seatLayoutRepository.existsByPerformanceId(performanceId)) {
      log.info("이미 좌석 배치도가 생성된 공연입니다. 좌석 생성을 스킵합니다. performanceId: {}", performanceId);
      return;
    }

    SeatLayout savedLayout =
        seatLayoutRepository.saveAndFlush(
            SeatLayout.builder()
                .performanceId(performanceId)
                .totalRows(DEFAULT_TOTAL_ROWS)
                .maxCols(DEFAULT_MAX_COLS)
                .build());

    List<Seat> seats = createSeats(savedLayout);
    seatRepository.saveAll(seats);

    log.info("기본 좌석 배치도와 좌석을 생성했습니다. performanceId: {}, seats: {}", performanceId, seats.size());
  }

  private List<Seat> createSeats(SeatLayout seatLayout) {
    List<Seat> seats = new ArrayList<>(seatLayout.getTotalRows() * seatLayout.getMaxCols());

    for (int row = 0; row < seatLayout.getTotalRows(); row++) {
      char rowName = (char) ('A' + row);

      for (int col = 1; col <= seatLayout.getMaxCols(); col++) {
        seats.add(
            Seat.builder()
                .seatLayoutId(seatLayout.getId())
                .performanceId(seatLayout.getPerformanceId())
                .seatNumber(rowName + "-" + col)
                .seatStatus(SeatStatus.AVAILABLE)
                .holdExpiredAt(null)
                .build());
      }
    }

    return seats;
  }
}
