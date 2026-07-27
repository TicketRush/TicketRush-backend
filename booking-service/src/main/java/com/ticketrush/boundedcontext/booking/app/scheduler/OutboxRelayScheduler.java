package com.ticketrush.boundedcontext.booking.app.scheduler;

import com.ticketrush.global.outbox.OutboxRelayService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * booking-service의 Outbox relay 스케줄러.
 *
 * <p>{@code app.event-publisher.type=outbox}일 때만 등록되며(그때만 {@link OutboxRelayService} 빈이 존재), 다중
 * 인스턴스 중복 실행은 ShedLock으로 방지한다. 자기 소유 애그리거트({@code app.outbox.aggregate-types})만 발행한다.
 */
@Component
@ConditionalOnExpression("'${app.event-publisher.type}' == 'outbox'")
@RequiredArgsConstructor
public class OutboxRelayScheduler {

  private final OutboxRelayService outboxRelayService;

  // 주기는 5초 고정이다. 이 값을 낮춰도 실질 주기는 3초 밑으로 내려가지 않는다 — 아래
  // lockAtLeastFor="3s"가 배치가 즉시 끝나도 락을 3초간 붙잡기 때문이다(#489). 처리량이 필요하면
  // app.outbox.batch-size를 올린다(300/5s = 60/s). 그쪽이 코드 변경이 없고 조회 윈도의 in-flight
  // 슬롯 여유도 함께 커진다. 주기를 진짜로 낮추려면 lockAtLeastFor부터 재조정해야 하는데, 그 값은
  // 다중 인스턴스 failover 지연과 맞물린 별개 축이다.
  @Scheduled(fixedDelay = 5000)
  // 발행은 비동기(콜백)라 dispatch가 빠르게 끝나므로 lock은 짧게 유지한다(장애 시 failover 지연 단축).
  @SchedulerLock(name = "outboxRelay-booking", lockAtLeastFor = "3s", lockAtMostFor = "1m")
  public void relay() {
    outboxRelayService.relayBatch();
  }
}
