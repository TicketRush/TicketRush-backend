package com.ticketrush.global.outbox;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발행 완료(SENT)된 Outbox row를 보존 기간이 지난 뒤 정리하는 retention 배치.
 *
 * <p>성공 즉시 하드삭제하지 않고 일정 기간 보존(감사/디버깅) 후 삭제해 relay 재조회와의 레이스를 피하고 삭제 부하를 분산한다. 여러 서비스가 같은 테이블을 공유하므로
 * 자기 소유 aggregateType의 row만 삭제한다.
 */
@Slf4j
@Service
@ConditionalOnExpression("'${app.event-publisher.type}' == 'outbox'")
@RequiredArgsConstructor
public class OutboxRetentionService {

  private final OutboxRepository outboxRepository;
  private final OutboxProperties outboxProperties;

  @Transactional
  public int purgeExpiredSent() {
    List<String> aggregateTypes = outboxProperties.getAggregateTypes();
    if (aggregateTypes == null || aggregateTypes.isEmpty()) {
      return 0;
    }

    LocalDateTime threshold = LocalDateTime.now().minusHours(outboxProperties.getRetentionHours());
    int deleted = outboxRepository.deleteSentBefore(aggregateTypes, OutboxStatus.SENT, threshold);
    if (deleted > 0) {
      log.info("Outbox retention: SENT row {}건 삭제 (threshold={})", deleted, threshold);
    }
    return deleted;
  }
}
