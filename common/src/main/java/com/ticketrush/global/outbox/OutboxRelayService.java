package com.ticketrush.global.outbox;

import com.ticketrush.global.event.DomainEventEnvelope;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 테이블의 미발송/실패 이벤트를 폴링해 실제 Kafka로 발행하는 relay.
 *
 * <p>{@code app.event-publisher.type=outbox}일 때만 활성화되며, 서비스별 스케줄러가 {@link #relayBatch()}를 주기적으로
 * 호출한다. 여러 서비스가 같은 outbox 테이블을 공유하므로 {@link OutboxProperties#getAggregateTypes()}로 자기 소유 이벤트만 발행한다.
 *
 * <p>발행 성공 시 {@link OutboxStatus#SENT}, 실패 시 {@link OutboxStatus#FAILED}(다음 폴링에서 재시도)로 전이한다. 발행은
 * at-least-once이며, 중복은 컨슈머가 {@code eventId}로 멱등 처리한다.
 */
@Slf4j
@Service
@ConditionalOnExpression("'${app.event-publisher.type}' == 'outbox'")
@RequiredArgsConstructor
public class OutboxRelayService {

  private static final long SEND_TIMEOUT_SECONDS = 10L;
  private static final List<OutboxStatus> RELAY_TARGET_STATUSES =
      List.of(OutboxStatus.PENDING, OutboxStatus.FAILED);

  private final OutboxRepository outboxRepository;
  private final KafkaTemplate<String, DomainEventEnvelope> kafkaTemplate;
  private final OutboxProperties outboxProperties;

  @Transactional
  public void relayBatch() {
    List<String> aggregateTypes = outboxProperties.getAggregateTypes();
    if (aggregateTypes == null || aggregateTypes.isEmpty()) {
      return;
    }

    List<OutboxEntity> rows =
        outboxRepository.findByAggregateTypeInAndStatusInOrderByIdAsc(
            aggregateTypes,
            RELAY_TARGET_STATUSES,
            PageRequest.of(0, outboxProperties.getBatchSize()));

    for (OutboxEntity row : rows) {
      relayOne(row);
    }
  }

  private void relayOne(OutboxEntity row) {
    try {
      // 발행 결과에 따라 상태를 전이해야 하므로 동기 대기한다. batchSize가 작아 허용 가능하며,
      // 필요 시 비동기 일괄 발행으로 최적화할 수 있다.
      kafkaTemplate.send(toMessage(row)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      row.markSent(LocalDateTime.now());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      row.markFailed(errorMessage(e));
      log.error("Outbox relay interrupted. eventId={}", row.getEventId(), e);
    } catch (Exception e) {
      row.markFailed(errorMessage(e));
      log.error("Outbox relay failed. eventId={}, topic={}", row.getEventId(), row.getTopic(), e);
    }
  }

  private Message<DomainEventEnvelope> toMessage(OutboxEntity row) {
    // of()는 새 eventId를 생성하므로 쓰지 않고, 저장된 eventId를 보존해 envelope를 재구성한다.
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            row.getEventId(),
            row.getEventType(),
            toInstant(row.getCreatedAt()),
            row.getTopic(),
            row.getPayload(),
            row.getTraceId());

    // KafkaDomainEventPublisher와 동일한 헤더 형태로 발행한다.
    return MessageBuilder.withPayload(envelope)
        .setHeader(KafkaHeaders.TOPIC, row.getTopic())
        .setHeader(KafkaHeaders.KEY, row.getMessageKey())
        .setHeader("eventType", envelope.eventType())
        .setHeader("eventId", envelope.eventId())
        .build();
  }

  private Instant toInstant(LocalDateTime createdAt) {
    // outbox는 envelope의 원본 createdAt(Instant)을 보존하지 않아 행 auditing 시각으로 근사한다.
    return createdAt == null ? Instant.now() : createdAt.atZone(ZoneId.systemDefault()).toInstant();
  }

  private String errorMessage(Exception e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }
}
