package com.ticketrush.global.outbox;

import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.event.DomainEventEnvelope;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * Outbox 테이블의 미발송/실패 이벤트를 폴링해 실제 Kafka로 발행하는 relay.
 *
 * <p>{@code app.event-publisher.type=outbox}일 때만 활성화되며, 서비스별 스케줄러가 {@link #relayBatch()}를 주기적으로
 * 호출한다. 여러 서비스가 같은 outbox 테이블을 공유하므로 {@link OutboxProperties#getAggregateTypes()}로 자기 소유 이벤트만 발행한다.
 *
 * <p>발행은 비동기(`whenComplete`)로 수행하고, 결과에 따른 상태 전이는 {@link OutboxStatusUpdater}가 별도 트랜잭션에서 처리한다(콜백이
 * relay 트랜잭션 밖 프로듀서 IO 스레드에서 실행되기 때문). 발행은 at-least-once이며, 중복은 컨슈머가 {@code eventId}로 멱등 처리한다.
 */
@Slf4j
@Service
@ConditionalOnExpression("'${app.event-publisher.type}' == 'outbox'")
@RequiredArgsConstructor
public class OutboxRelayService {

  private static final List<OutboxStatus> RELAY_TARGET_STATUSES =
      List.of(OutboxStatus.PENDING, OutboxStatus.FAILED);

  private final OutboxRepository outboxRepository;
  private final KafkaTemplate<String, DomainEventEnvelope> kafkaTemplate;
  private final OutboxProperties outboxProperties;
  private final OutboxStatusUpdater outboxStatusUpdater;
  private final MeterRegistry meterRegistry;

  private final AtomicLong backlog = new AtomicLong();

  /** 이 서비스가 소유한 aggregateTypes의 적체(PENDING/FAILED) 건수를 노출하는 Gauge를 등록한다(#335). */
  @PostConstruct
  public void registerBacklogGauge() {
    Gauge.builder(MetricNames.OUTBOX_BACKLOG, backlog, AtomicLong::get).register(meterRegistry);
  }

  public void relayBatch() {
    List<String> aggregateTypes = outboxProperties.getAggregateTypes();
    if (aggregateTypes == null || aggregateTypes.isEmpty()) {
      return;
    }

    backlog.set(
        outboxRepository.countByAggregateTypeInAndStatusIn(aggregateTypes, RELAY_TARGET_STATUSES));

    int batchSize = outboxProperties.getBatchSize();
    if (batchSize < 1) {
      log.warn("Outbox relay batchSize가 1 미만으로 설정되어 실행을 건너뜁니다. batchSize={}", batchSize);
      return;
    }

    List<OutboxEntity> rows =
        outboxRepository.findByAggregateTypeInAndStatusInOrderByIdAsc(
            aggregateTypes, RELAY_TARGET_STATUSES, PageRequest.of(0, batchSize));

    for (OutboxEntity row : rows) {
      dispatch(row);
    }
  }

  private void dispatch(OutboxEntity row) {
    Long id = row.getId();
    String eventId = row.getEventId();
    String topic = row.getTopic();
    // 비동기 발행 후 완료 콜백에서 상태를 전이한다. 콜백은 프로듀서 IO 스레드(relay tx 밖)에서 실행되므로
    // OutboxStatusUpdater가 REQUIRES_NEW 트랜잭션으로 처리한다.
    kafkaTemplate
        .send(toMessage(row))
        .whenComplete(
            (result, ex) -> {
              if (ex == null) {
                outboxStatusUpdater.markSuccess(id);
              } else {
                outboxStatusUpdater.markFail(id, errorMessage(ex));
                log.error("Outbox relay failed. eventId={}, topic={}", eventId, topic, ex);
              }
            });
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

  private String errorMessage(Throwable ex) {
    // 완료 예외는 래핑(CompletionException 등)될 수 있으므로 근본 원인(cause)을 우선 남긴다.
    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }
}
