package com.ticketrush.global.eventpublisher;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.outbox.OutboxEntity;
import com.ticketrush.global.outbox.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭셔널 Outbox 패턴의 이벤트 발행자.
 *
 * <p>도메인 이벤트를 즉시 Kafka로 보내지 않고, 호출자(유즈케이스)의 <b>활성 트랜잭션에 참여</b>해 {@link OutboxEntity}로 저장한다. 비즈니스 상태
 * 변경과 Outbox INSERT가 같은 커밋으로 원자성(Atomicity)을 가지며, 커밋 이후 별도 폴링 relay가 {@code PENDING} row를 Kafka로
 * 발행한다.
 *
 * <p>{@link KafkaDomainEventPublisher}와 상호배타적으로 활성화되며({@code app.event-publisher.type}), Kafka 구현과
 * 달리 {@code afterCommit} 지연 전송이나 {@code @Transactional}을 두지 않는 것이 핵심 차이다.
 */
@Component
@ConditionalOnExpression("'${app.event-publisher.type}' == 'outbox'")
@RequiredArgsConstructor
public class OutboxEventPublisher implements EventPublisher {

  private static final String EVENT_BASE_PACKAGE_MARKER = ".shared.";

  private final OutboxRepository outboxRepository;
  private final JsonConverter jsonConverter;

  @Override
  public void publish(DomainEvent event) {
    // 활성 트랜잭션이 없으면 Outbox INSERT가 자체 트랜잭션으로 커밋돼 비즈니스 변경과의 원자성이 깨진다.
    // 반드시 호출부의 트랜잭션 내부에서 사용하도록 강제한다.
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "OutboxEventPublisher.publish()는 활성 트랜잭션 내부에서만 호출해야 합니다. "
              + "비즈니스 변경과 Outbox 저장의 원자성을 보장하려면 호출부를 @Transactional 등으로 감싸세요.");
    }

    String payload = jsonConverter.serialize(event);
    DomainEventEnvelope envelope = DomainEventEnvelope.of(event, payload);

    String aggregateType = resolveAggregateType(event);
    String aggregateId = event.aggregateId(); // 이벤트를 발생시킨 애그리거트의 PK
    String messageKey = event.key(); // Kafka 파티션 키 (aggregateId와 다를 수 있음)

    outboxRepository.save(OutboxEntity.from(envelope, aggregateType, aggregateId, messageKey));
  }

  /**
   * 이벤트 클래스 패키지에서 애그리거트 타입을 유도한다.
   *
   * <p>이벤트는 모두 {@code com.ticketrush.shared.<aggregate>.event.XxxEvent} 구조로 일관되므로, {@code shared.}
   * 다음 세그먼트를 추출해 첫 글자를 대문자화한다 (예: {@code booking} → {@code "Booking"}). 패턴에 맞지 않으면 이벤트의 단순 클래스명으로
   * 폴백한다.
   */
  private String resolveAggregateType(DomainEvent event) {
    String packageName = event.getClass().getPackageName();
    int markerIndex = packageName.indexOf(EVENT_BASE_PACKAGE_MARKER);
    if (markerIndex < 0) {
      return event.getClass().getSimpleName();
    }

    String afterMarker = packageName.substring(markerIndex + EVENT_BASE_PACKAGE_MARKER.length());
    int nextDot = afterMarker.indexOf('.');
    String aggregate = nextDot < 0 ? afterMarker : afterMarker.substring(0, nextDot);
    if (aggregate.isEmpty()) {
      return event.getClass().getSimpleName();
    }

    return Character.toUpperCase(aggregate.charAt(0)) + aggregate.substring(1);
  }
}
