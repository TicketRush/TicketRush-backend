package com.ticketrush.global.outbox;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka 비동기 발행 콜백에서 Outbox row의 상태를 전이한다.
 *
 * <p>콜백은 프로듀서 IO 스레드(relay 트랜잭션 밖)에서 실행되므로, 각 전이는 {@link Propagation#REQUIRES_NEW}로 독립 트랜잭션에서 수행해
 * 커밋한다. 콜백 사이에 row가 삭제/변경됐을 수 있어 조회 실패는 조용히 무시한다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.event-publisher.type}' == 'outbox'")
@RequiredArgsConstructor
public class OutboxStatusUpdater {

  private final OutboxRepository outboxRepository;
  private final OutboxProperties outboxProperties;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markSuccess(Long id) {
    outboxRepository.findById(id).ifPresent(row -> row.markSent(LocalDateTime.now()));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markFail(Long id, String lastError) {
    outboxRepository
        .findById(id)
        .ifPresent(
            row -> {
              row.markFailed(lastError, outboxProperties.getMaxRetries());
              if (row.getStatus() == OutboxStatus.DEAD) {
                // SlackNotifier(#50) 도입 전까지 CRITICAL 로그로 대체한다.
                log.error(
                    "[CRITICAL] Outbox 이벤트가 재시도 상한({})을 초과해 DEAD 처리되었습니다. eventId={}, lastError={}",
                    outboxProperties.getMaxRetries(),
                    row.getEventId(),
                    lastError);
              }
            });
  }
}
