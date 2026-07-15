package com.ticketrush.boundedcontext.performance.app.scheduler;

import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceOpenBookingUseCase;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true) // test 프로파일은 Redis가 없어 false로 끈다
public class PerformanceStatusScheduler {

  private final PerformanceOpenBookingUseCase performanceOpenBookingUseCase;

  // 정각 티켓 오픈 체감 지연을 줄이기 위해 10초 주기로 실행
  @Scheduled(fixedDelay = 10000)
  @SchedulerLock(
      name = "schedulePerformanceOpenBookingLock",
      lockAtLeastFor = "9s", // 서버 간 시계 오차(Clock Skew)로 인한 즉각적인 중복 실행 방지
      lockAtMostFor = "1m" // 노드가 죽었을 때 락이 자동으로 풀리는 최대 시간
      )
  public void schedulePerformanceOpenBooking() {
    performanceOpenBookingUseCase.execute();
  }
}
