package com.ticketrush.boundedcontext.seat.app.scheduler;

import com.ticketrush.global.outbox.OutboxRetentionService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * seat-service의 Outbox retention 스케줄러.
 *
 * <p>{@code app.event-publisher.type=outbox}일 때만 등록되며, 발행 완료(SENT) 후 보존 기간이 지난 자기 소유 row를 주기적으로
 * 삭제한다. 다중 인스턴스 중복 실행은 ShedLock으로 방지한다.
 */
@Component
@ConditionalOnExpression("'${app.event-publisher.type}' == 'outbox'")
@RequiredArgsConstructor
public class OutboxRetentionScheduler {

  private final OutboxRetentionService outboxRetentionService;

  @Scheduled(fixedDelay = 3600000) // 1시간
  @SchedulerLock(name = "outboxRetention-seat", lockAtLeastFor = "10s", lockAtMostFor = "5m")
  public void purge() {
    outboxRetentionService.purgeExpiredSent();
  }
}
