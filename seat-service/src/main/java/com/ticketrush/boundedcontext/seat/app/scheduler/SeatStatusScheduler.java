package com.ticketrush.boundedcontext.seat.app.scheduler;

import com.ticketrush.boundedcontext.seat.app.usecase.SeatReleaseExpiredUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeatStatusScheduler {

  private final SeatReleaseExpiredUseCase seatReleaseExpiredUseCase;

  // 실시간 처리는 Redis Event Listener가 담당하므로, 스케줄러는 1분(60,000ms) 주기의 Fallback 역할만 수행
  @Scheduled(fixedDelay = 60000)
  @SchedulerLock(
      name = "scheduleReleaseExpiredHoldsFallbackLock",
      lockAtLeastFor = "50s", // 서버 간 시계 오차(Clock Skew)로 인한 즉각적인 중복 실행 방지
      lockAtMostFor = "2m" // 노드가 죽었을 때 락이 자동으로 풀리는 최대 시간
      )
  public void scheduleReleaseExpiredHoldsFallback() {
    log.info("Fallback 스케줄러 동작: 유실된 만료 좌석 검사 시작");
    seatReleaseExpiredUseCase.execute();
  }
}
