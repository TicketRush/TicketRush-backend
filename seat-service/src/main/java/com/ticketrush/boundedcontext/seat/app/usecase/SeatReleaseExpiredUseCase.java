package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseExpiredUseCase {

  private final SeatRepository seatRepository;
  private final SeatStatusEventPublisher seatStatusEventPublisher;

  @Transactional
  public void execute() {
    LocalDateTime now = LocalDateTime.now();
    List<Seat> expiredSeats = seatRepository.findExpiredHoldSeats(SeatStatus.HOLD, now);

    for (Seat seat : expiredSeats) {
      seat.releaseHold();
      seatStatusEventPublisher.publishAfterCommit(seat);
    }

    if (!expiredSeats.isEmpty()) {
      log.info("만료된 좌석 {}개의 상태를 AVAILABLE로 롤백했습니다. 기준 시간: {}", expiredSeats.size(), now);
    }
  }
}
