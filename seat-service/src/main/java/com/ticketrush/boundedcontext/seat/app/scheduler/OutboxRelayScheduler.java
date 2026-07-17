package com.ticketrush.boundedcontext.seat.app.scheduler;

import com.ticketrush.global.outbox.OutboxRelayService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * seat-service의 Outbox relay 스케줄러.
 *
 * <p>{@code app.event-publisher.type=outbox}일 때만 등록되며(그때만 {@link OutboxRelayService} 빈이 존재), 다중
 * 인스턴스 중복 실행은 ShedLock으로 방지한다. 자기 소유 애그리거트({@code app.outbox.aggregate-types})만 발행한다.
 */
@Component
@ConditionalOnExpression("'${app.event-publisher.type}' == 'outbox'")
@RequiredArgsConstructor
public class OutboxRelayScheduler {

  private final OutboxRelayService outboxRelayService;

  @Scheduled(fixedDelay = 5000)
  // 발행은 비동기(콜백)라 dispatch가 빠르게 끝나므로 lock은 짧게 유지한다(장애 시 failover 지연 단축).
  @SchedulerLock(name = "outboxRelay-seat", lockAtLeastFor = "3s", lockAtMostFor = "1m")
  public void relay() {
    outboxRelayService.relayBatch();
  }
}
