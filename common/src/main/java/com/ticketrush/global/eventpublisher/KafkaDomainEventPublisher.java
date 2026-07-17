package com.ticketrush.global.eventpublisher;

import com.ticketrush.global.event.DomainEvent;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.json.JsonConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@ConditionalOnExpression("'${app.event-publisher.type}' == 'kafka'")
@RequiredArgsConstructor
public class KafkaDomainEventPublisher implements EventPublisher {

  private final KafkaTemplate<String, DomainEventEnvelope> kafkaTemplate;
  private final JsonConverter jsonConverter;

  @Override
  public void publish(DomainEvent event) {
    String payload = jsonConverter.serialize(event);
    DomainEventEnvelope envelope = DomainEventEnvelope.of(event, payload);

    Message<DomainEventEnvelope> message =
        MessageBuilder.withPayload(envelope)
            .setHeader(KafkaHeaders.TOPIC, event.topic())
            .setHeader(KafkaHeaders.KEY, event.key())
            .setHeader("eventType", envelope.eventType())
            .setHeader("eventId", envelope.eventId())
            .build();

    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              sendMessage(message, event, envelope);
            }
          });
    } else {
      sendMessage(message, event, envelope);
    }
  }

  private void sendMessage(
      Message<DomainEventEnvelope> message, DomainEvent event, DomainEventEnvelope envelope) {
    kafkaTemplate
        .send(message)
        .whenComplete(
            (result, ex) -> {
              if (ex != null) {
                log.error(
                    "Failed to publish event to Kafka topic: {}, eventType={}, eventId={}",
                    event.topic(),
                    envelope.eventType(),
                    envelope.eventId(),
                    ex);
              }
            });
  }
}
