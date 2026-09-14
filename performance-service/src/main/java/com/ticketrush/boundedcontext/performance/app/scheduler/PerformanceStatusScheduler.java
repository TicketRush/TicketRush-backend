package com.ticketrush.boundedcontext.performance.app.scheduler;

import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceCloseShowUseCase;
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
  private final PerformanceCloseShowUseCase performanceCloseShowUseCase;

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

  /**
   * 공연 시작 시각이 지난 ON_SALE 공연을 CLOSED로 전환한다 (#651).
   *
   * <p>1분 주기다. 사용자 목록은 시작 시각 조건으로 지난 공연을 즉시 걸러내므로 상태 전환까지 급할 이유가 없고, 오픈 스케줄러의 10초 근거(정각 오픈 체감)는 여기
   * 없다. 락은 오픈 스케줄러와 공유하지 않는다 — 둘은 대상 상태가 달라 서로 기다릴 이유가 없다.
   */
  @Scheduled(fixedDelay = 60_000)
  @SchedulerLock(
      name = "schedulePerformanceCloseShowLock",
      lockAtLeastFor = "50s", // 서버 간 시계 오차로 인한 즉각적인 중복 실행 방지
      lockAtMostFor = "2m" // 노드가 죽었을 때 락이 자동으로 풀리는 최대 시간
      )
  public void schedulePerformanceCloseShow() {
    performanceCloseShowUseCase.execute();
  }
}
