package com.ticketrush.global.outbox;

import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.event.DomainEventEnvelope;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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
 * 만큼 중복이 배로 늘어난다({@code inFlight} 참고). 남는 중복은 재시작·상태 전이 실패·ShedLock 만료 중 동시 실행처럼 표시가 무력해지는 경우다.
 */
@Slf4j
@Service
@ConditionalOnExpression("'${app.event-publisher.type}' == 'outbox'")
@RequiredArgsConstructor
public class OutboxRelayService {

  private static final List<OutboxStatus> RELAY_TARGET_STATUSES =
      List.of(OutboxStatus.PENDING, OutboxStatus.FAILED);

  /**
   * in-flight 표시의 유효기간. 프로듀서는 {@code delivery.timeout.ms}(KafkaConfig, 120초) 안에 성공이든 실패든 콜백을 돌려주므로
   * 그 이상 남아 있는 항목은 콜백이 유실된 것이다. 여유를 얹어 잡는다 — 짧게 잡으면 정상 지연을 중복 발행으로 되돌린다.
   */
  private static final long IN_FLIGHT_TIMEOUT_MS = 180_000L;

  private final OutboxRepository outboxRepository;
  private final KafkaTemplate<String, DomainEventEnvelope> kafkaTemplate;
  private final OutboxProperties outboxProperties;
  private final OutboxStatusUpdater outboxStatusUpdater;
  private final MeterRegistry meterRegistry;

  private final AtomicLong backlog = new AtomicLong();

  /**
   * 발행을 띄운 행의 id → 띄운 시각(epoch millis).
   *
   * <p>없으면 같은 행이 반복 발행된다. 조회는 PENDING/FAILED를 보는데 SENT 전이는 프로듀서 콜백에서만 일어나므로, 콜백 커밋이 다음 폴링보다 늦으면
   * {@link OutboxRepository#findOldestRelayTargets}가 같은 행을 다시 집는다. #344 측정에서 outbox 1,324행에 발행
   * 4,090건(3.09배)이 나왔고 그 중복은 전부 컨슈머 Inbox가 걸러내느라 단일 컨슈머 스레드 처리량의 68%를 썼다.
   *
   * <p><b>시각을 함께 들고 폴링마다 낡은 항목을 걷어내는 이유</b>는 이 표시가 새면 조회 윈도(id 오름차순 {@code LIMIT batchSize})의 슬롯을
   * 영구 점유하기 때문이다. 슬롯이 batchSize만큼 잠기면 릴레이가 완전히 멈추고 재시작 외에 복구 수단이 없다. {@code catch}가 {@code Error}를
   * 잡지 않는 것도 의도이므로(삼키면 안 된다) 그 경로의 누수는 이 청소가 받아낸다. 최악 지연이 {@link #IN_FLIGHT_TIMEOUT_MS}로 유한해진다.
   *
   * <p>인스턴스 로컬이라 보호 범위에 한계가 있다. relay는 ShedLock으로 한 번에 한 노드만 도는 것이 정상이지만, 락은 Redis TTL 기반이고 실행 중
   * 연장하지 않으므로 {@code lockAtMostFor}(1분)를 넘겨 도는 폴링이 있으면 다른 노드가 동시에 들어올 수 있다. 그 구간에서는 두 노드의 표시가 서로를
   * 몰라 중복이 다시 생긴다 — 막지 못하는 경우다. 중복 자체는 컨슈머가 {@code eventId}로 걸러내므로 정합성 문제는 없다.
   */
  private final Map<Long, Long> inFlight = new ConcurrentHashMap<>();

  /**
   * 이 서비스가 소유한 aggregateTypes의 적체(PENDING/FAILED) 건수 Gauge(#335)와, 그중 콜백을 기다리는 중인 건수 Gauge(#483)를
   * 등록한다.
   *
   * <p>backlog는 in-flight 행도 PENDING으로 세므로 단독으로는 "콜백 대기(정상)"와 "릴레이 정지(장애)"를 구분하지 못한다. 두 곡선을 겹쳐 봐야
   * 갈린다 — in-flight가 0과 batchSize 사이에서 출렁이면 정상이고, batchSize에 붙어 정체하면 콜백 유실로 조회 윈도가 잠긴 것이며(위 {@code
   * inFlight} javadoc의 슬롯 고갈), 0에 고정이면 릴레이가 행을 아예 집지 못하고 있다. 판별표는 {@code docs/load-test-guide.md}
   * §10.3에 있다.
   *
   * <p>단, {@code backlog}는 이 메서드가 아니라 {@link #relayBatch()} 안에서만 갱신되므로 릴레이가 멈추면 마지막 값이 그대로 노출된다. 두
   * 게이지가 동시에 정지해 있으면 값이 낮아도 안전 신호가 아니라 관측이 멎은 것이다.
   */
  @PostConstruct
  public void registerGauges() {
    Gauge.builder(MetricNames.OUTBOX_BACKLOG, backlog, AtomicLong::get).register(meterRegistry);
    Gauge.builder(MetricNames.OUTBOX_IN_FLIGHT, inFlight, Map::size).register(meterRegistry);
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

    List<OutboxEntity> rows = findOldestRelayTargets(aggregateTypes, batchSize);

    long now = System.currentTimeMillis();
    // 콜백이 유실된 항목을 걷어낸다. 없으면 조회 윈도의 슬롯이 영구히 잠긴다(inFlight javadoc).
    inFlight.entrySet().removeIf(entry -> now - entry.getValue() > IN_FLIGHT_TIMEOUT_MS);

    for (OutboxEntity row : rows) {
      // null이 아니면 직전 배치가 띄운 발행이 아직 콜백을 못 받은 것이다. 다시 보내지 않는다.
      if (inFlight.putIfAbsent(row.getId(), now) == null) {
        dispatch(row);
      }
    }
  }

  /**
   * 릴레이 대상 중 가장 오래된(id 오름차순) {@code batchSize}건을 가져온다.
   *
   * <p>조회를 (aggregateType, status) 조합별로 쪼개 부르는 이유는 {@link OutboxRepository#findOldestRelayTargets}
   * javadoc 참고(#483). 각 조합에서 가장 오래된 {@code batchSize}건씩 가져오므로 전체 기준 오래된 {@code batchSize}건은 반드시 이
   * 합집합 안에 있다 — 오래된 순 발행 semantics는 그대로다. 병합 정렬 대상은 최대 {@code aggregateTypes × 2 × batchSize}건이다.
   *
   * <p><b>쪼개면서 잃은 것은 조회의 원자성이다.</b> 각 쿼리가 서로 다른 스냅샷을 보므로, PENDING 조회와 FAILED 조회 사이에 어떤 행이 {@code
   * markFail}로 전이하면 같은 행이 양쪽에 잡혀 배치 슬롯을 두 칸 먹는다. 중복 발행은 {@code inFlight.putIfAbsent}가 막고 상태 전이가
   * 단조(FAILED→PENDING 역행 없음)라 행 유실은 생기지 않는다 — 그 폴링의 유효 배치가 한 건 줄 뿐이라 감수한다.
   */
  private List<OutboxEntity> findOldestRelayTargets(List<String> aggregateTypes, int batchSize) {
    List<OutboxEntity> rows = new ArrayList<>();
    for (String aggregateType : aggregateTypes) {
      for (OutboxStatus status : RELAY_TARGET_STATUSES) {
        rows.addAll(
            outboxRepository.findOldestRelayTargets(aggregateType, status.name(), batchSize));
      }
    }
    rows.sort(Comparator.comparingLong(OutboxEntity::getId));
    return rows.size() > batchSize ? rows.subList(0, batchSize) : rows;
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
                } catch (RuntimeException transitionError) {
                  // 전이 실패는 행을 PENDING/FAILED로 남겨 다음 폴링이 재발행하게 만든다. 즉 이 예외가
                  // 남은 중복 발생원인데, whenComplete가 돌려주는 future를 버리므로 여기서 안 남기면
                  // 어디에도 흔적이 없다.
                  log.error(
                      "Outbox 상태 전이 실패. eventId={}, topic={}", eventId, topic, transitionError);
                } finally {
                  // 상태 전이 성패와 무관하게 푼다. 전이가 실패했다면 행이 PENDING/FAILED로 남아
                  // 다음 폴링이 정상적으로 다시 집어야 하므로, 여기서 잠가두면 영영 발행되지 않는다.
                  inFlight.remove(id);
                }
              });
    } catch (RuntimeException e) {
      // send()가 콜백 없이 즉시 터지는 경로(직렬화 실패 등). 재던지면 relayBatch의 루프가 끊겨 이 폴링의
      // 뒤쪽 행이 전부 취소되고, 조회가 id 오름차순이라 같은 행이 매 폴링마다 선두에 다시 서서 뒤쪽이
      // 영영 발행되지 않는다(head-of-line 블록). 실패로 기록해 retryCount를 올리고 — 그래야
      // maxRetries 초과 시 DEAD로 빠져 알림이 나간다 — 다음 행으로 넘어간다.
      log.error("Outbox relay send 동기 실패. eventId={}, topic={}", eventId, topic, e);
      try {
        outboxStatusUpdater.markFail(id, errorMessage(e));
      } catch (RuntimeException markError) {
        log.error("Outbox 동기 실패 기록 실패. eventId={}", eventId, markError);
      } finally {
        inFlight.remove(id);
      }
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
