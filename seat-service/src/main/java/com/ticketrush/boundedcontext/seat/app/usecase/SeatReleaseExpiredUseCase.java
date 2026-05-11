package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatReleaseExpiredUseCase {

  private final SeatRepository seatRepository;

  @Transactional
  public void execute() {
    LocalDateTime now = LocalDateTime.now();
    // 만료 시간이 현재 시간보다 이전이면서 상태가 HOLD인 좌석을 AVAILABLE로 변경
    int releasedCount =
        seatRepository.releaseExpiredSeats(SeatStatus.AVAILABLE, SeatStatus.HOLD, now);

    if (releasedCount > 0) {
      log.info("만료된 좌석 {}개의 상태를 AVAILABLE로 롤백했습니다. 기준 시간: {}", releasedCount, now);
    }
  }
}
