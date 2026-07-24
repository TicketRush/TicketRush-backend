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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p>다만 at-least-once가 "폴링마다 같은 행을 다시 보내도 된다"는 뜻은 아니다. 상태 전이가 콜백에서만 일어나므로 in-flight 표시가 없으면 콜백이 늦는
 * 만큼 중복이 배로 늘어난다({@code inFlight} 참고). 남는 중복은 재시작·페일오버로 표시가 사라지는 경우뿐이다.
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

  /**
   * 발행을 띄웠지만 아직 완료 콜백이 상태를 전이하지 못한 행의 id.
   *
   * <p>없으면 같은 행이 반복 발행된다. 조회는 PENDING/FAILED를 보는데 SENT 전이는 프로듀서 콜백에서만 일어나므로, 콜백 커밋이 다음 폴링보다 늦으면
   * {@code findBy...OrderByIdAsc}가 같은 행을 다시 집는다. #344 측정에서 outbox 1,324행에 발행 4,090건(3.09배)이 나왔고 그
   * 중복은 전부 컨슈머 Inbox가 걸러내느라 단일 컨슈머 스레드 처리량의 68%를 썼다.
   *
   * <p>인스턴스 로컬로 충분하다. relay는 ShedLock으로 한 번에 한 노드만 돌고, 페일오버로 다른 노드가 이어받아 재발행하는 경우는 이 클래스가 보장하는
   * at-least-once의 정상 범위다(중복은 컨슈머가 eventId로 멱등 처리한다). 콜백은 성공·실패 어느 쪽이든 반드시 도달하므로(프로듀서
   * delivery.timeout.ms) 항목이 영구히 남지 않는다.
   */
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

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
      // add()가 false면 직전 배치가 띄운 발행이 아직 콜백을 못 받은 것이다. 다시 보내지 않는다.
      if (inFlight.add(row.getId())) {
        dispatch(row);
      }
    }
  }

  private void dispatch(OutboxEntity row) {
    Long id = row.getId();
    String eventId = row.getEventId();
    String topic = row.getTopic();
    // 비동기 발행 후 완료 콜백에서 상태를 전이한다. 콜백은 프로듀서 IO 스레드(relay tx 밖)에서 실행되므로
    // OutboxStatusUpdater가 REQUIRES_NEW 트랜잭션으로 처리한다.
    try {
      kafkaTemplate
          .send(toMessage(row))
          .whenComplete(
              (result, ex) -> {
                try {
                  if (ex == null) {
                    outboxStatusUpdater.markSuccess(id);
                  } else {
                    outboxStatusUpdater.markFail(id, errorMessage(ex));
                    log.error("Outbox relay failed. eventId={}, topic={}", eventId, topic, ex);
                  }
                } finally {
                  // 상태 전이 성패와 무관하게 푼다. 전이가 실패했다면 행이 PENDING/FAILED로 남아
                  // 다음 폴링이 정상적으로 다시 집어야 하므로, 여기서 잠가두면 영영 발행되지 않는다.
                  inFlight.remove(id);
                }
              });
    } catch (RuntimeException e) {
      // send() 자체가 콜백 없이 즉시 터지는 경우(직렬화 실패 등) 콜백이 오지 않아 잠금이 남는다.
      inFlight.remove(id);
      throw e;
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

  private String errorMessage(Throwable ex) {
    // 완료 예외는 래핑(CompletionException 등)될 수 있으므로 근본 원인(cause)을 우선 남긴다.
    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }
}
