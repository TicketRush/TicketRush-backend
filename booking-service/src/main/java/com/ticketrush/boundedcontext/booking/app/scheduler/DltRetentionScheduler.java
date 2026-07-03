package com.ticketrush.boundedcontext.booking.app.scheduler;

import com.ticketrush.global.dlt.DltRetentionService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * booking-service의 DLT retention 스케줄러.
 *
 * <p>{@code app.dlt.monitor.enabled=true}일 때만 등록되며, 보존 기간이 지난 {@code dead_letter_record} row를 주기적으로
 * 삭제한다. 다중 인스턴스 중복 실행은 ShedLock으로 방지한다.
 */
@Component
@ConditionalOnProperty(prefix = "app.dlt.monitor", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class DltRetentionScheduler {

  private final DltRetentionService dltRetentionService;

  @Scheduled(fixedDelay = 3600000) // 1시간
  @SchedulerLock(name = "dltRetention-booking", lockAtLeastFor = "10s", lockAtMostFor = "5m")
  public void purge() {
    dltRetentionService.purgeExpired();
  }
}
