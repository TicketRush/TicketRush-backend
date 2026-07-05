package com.ticketrush.global.inbox;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbox retention의 배치 1개를 <b>독립 트랜잭션</b>으로 삭제한다(#314).
 *
 * <p>{@code InboxRetentionService.purgeExpired()}는 트랜잭션을 열지 않고 이 메서드를 배치 단위로 반복 호출한다. 별도 빈으로 분리한
 * 이유는 같은 빈 내부 호출로는 프록시가 동작하지 않아 배치마다 새 트랜잭션이 열리지 않기 때문이다. 배치 간 커밋으로 락 보유 시간과 트랜잭션(undo/binlog) 크기를
 * 제한해, 최초 활성화 시 누적 row 대용량 삭제로 인한 긴 락·리플리케이션 지연을 방지한다.
 */
@Component
@ConditionalOnProperty(prefix = "app.inbox.retention", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class InboxRetentionBatchDeleter {

  private final InboxRepository inboxRepository;

  @Transactional
  public int deleteBatch(LocalDateTime threshold, int batchSize) {
    return inboxRepository.deleteCreatedBeforeInBatch(threshold, batchSize);
  }
}
