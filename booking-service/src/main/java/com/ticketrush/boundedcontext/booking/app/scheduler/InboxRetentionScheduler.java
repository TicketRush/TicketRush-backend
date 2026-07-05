package com.ticketrush.boundedcontext.booking.app.scheduler;

import com.ticketrush.global.inbox.InboxRetentionService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * booking-service의 Inbox retention 스케줄러(#314).
 *
 * <p>{@code app.inbox.retention.enabled=true}일 때만 등록되며, 보존 기간이 지난 {@code inbox} row를 주기적으로 삭제한다.
 * {@code inbox}는 전역 공유 테이블이므로 이 단일 서비스에서만 정리하며, 다중 인스턴스 중복 실행은 ShedLock으로 방지한다.
 */
@Component
@ConditionalOnProperty(prefix = "app.inbox.retention", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class InboxRetentionScheduler {

  private final InboxRetentionService inboxRetentionService;

  @Scheduled(fixedDelay = 3600000) // 1시간
  @SchedulerLock(name = "inboxRetention-booking", lockAtLeastFor = "10s", lockAtMostFor = "5m")
  public void purge() {
    inboxRetentionService.purgeExpired();
  }
}
