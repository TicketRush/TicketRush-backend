package com.ticketrush.global.eventpublisher;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.json.JsonConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 도메인 이벤트를 Kafka로 직접 발행한다({@code app.event-publisher.type=kafka}).
 *
 * <p>발행은 fire-and-forget이다 — 실패해도 호출자에게 전파되지 않고 {@link EventMessageSender}의 콜백에서 로그·카운터로만 남는다. 유실을
 * 허용할 수 없는 이벤트는 outbox 모드를 쓰거나, 발행과 별개로 복구 경로를 둬야 한다(#574).
 */
@Component
@ConditionalOnExpression("'${app.event-publisher.type}' == 'kafka'")
@RequiredArgsConstructor
public class KafkaDomainEventPublisher implements EventPublisher {

  private final EventMessageSender eventMessageSender;
  private final JsonConverter jsonConverter;

  @Override
  public void publish(DomainEvent event) {
    String payload = jsonConverter.serialize(event);
    DomainEventEnvelope envelope = DomainEventEnvelope.of(event, payload);
    Message<DomainEventEnvelope> message = eventMessageSender.toMessage(envelope, event.key());

    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              eventMessageSender.send(message, envelope);
            }
          });
    } else {
      eventMessageSender.send(message, envelope);
    }
  }
}
