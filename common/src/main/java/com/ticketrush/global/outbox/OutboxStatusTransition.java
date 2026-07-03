package com.ticketrush.global.outbox;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox row 상태 전이를 독립 트랜잭션({@link Propagation#REQUIRES_NEW})으로 수행하는 빈.
 *
 * <p>{@link OutboxStatusUpdater}가 트랜잭션 완료 후 외부 알림을 발송할 수 있도록, 상태 전이 로직을 별도 스프링 빈으로 분리했다. 같은 클래스의
 * private 메서드로 분리하면 Spring 프록시를 우회해 트랜잭션이 적용되지 않는(self-invocation) 문제를 이 구조로 회피한다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.event-publisher.type}' == 'outbox'")
@RequiredArgsConstructor
public class OutboxStatusTransition {

  private final OutboxRepository outboxRepository;
  private final OutboxProperties outboxProperties;

  /** 발행 성공을 SENT로 전이한다. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markSuccess(Long id) {
    outboxRepository.findById(id).ifPresent(row -> row.markSent(LocalDateTime.now()));
  }

  /**
   * 발행 실패를 기록하고 DEAD로 전이됐으면 {@link DeadInfo}를 반환한다. DEAD가 아니거나 row가 없으면 null을 반환한다.
   *
   * <p>반환값이 null이 아닌 경우 이 메서드의 트랜잭션은 이미 커밋된 상태이므로, 호출자는 DB 커넥션을 점유하지 않고 알림을 발송할 수 있다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public DeadInfo markFail(Long id, String lastError) {
    return outboxRepository
        .findById(id)
        .map(
            row -> {
              row.markFailed(lastError, outboxProperties.getMaxRetries());
              if (row.getStatus() == OutboxStatus.DEAD) {
                log.error(
                    "[CRITICAL] Outbox 이벤트가 재시도 상한({})을 초과해 DEAD 처리되었습니다. eventId={}, lastError={}",
                    outboxProperties.getMaxRetries(),
                    row.getEventId(),
                    sanitize(lastError));
                return new DeadInfo(row.getEventId(), outboxProperties.getMaxRetries());
              }
              return null;
            })
        .orElse(null);
  }

  /** 로그 인젝션 방지: 개행·탭 문자를 {@code _}로 대체한다. */
  private static String sanitize(String value) {
    return (value != null) ? value.replaceAll("[\\r\\n\\t]", "_") : null;
  }

  /** DEAD 전이 시 알림 발송에 필요한 정보를 담는 값 객체. */
  public record DeadInfo(String eventId, int maxRetries) {}
}
