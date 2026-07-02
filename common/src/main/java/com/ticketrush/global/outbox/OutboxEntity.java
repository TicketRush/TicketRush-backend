package com.ticketrush.global.outbox;

import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 트랜잭셔널 Outbox 패턴의 이벤트 저장 엔티티.
 *
 * <p>비즈니스 트랜잭션과 동일한 커밋으로 이 row가 저장되고, 폴링 스케줄러가 {@link OutboxStatus#PENDING} 상태의 row를 읽어 Kafka로
 * 발행한다. 폴링 조회가 빠르도록 {@code (status, created_at)} 복합 인덱스를 둔다.
 *
 * <p>이 이슈(#100) 범위는 엔티티와 스키마 설계까지이며, Repository·발행자·폴링 스케줄러·상태 전이 실행 로직은 후속 이슈에서 추가된다.
 */
@Entity
@Table(
    name = "outbox",
    indexes = {
      @Index(name = "idx_outbox_status_created_at", columnList = "status, created_at"),
      @Index(name = "uk_outbox_event_id", columnList = "event_id", unique = true)
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "outbox_id"))
public class OutboxEntity extends AutoIdBaseEntity {

  /* 이벤트 고유 ID(UUID). 컨슈머 멱등키이자 동일 이벤트의 Outbox 중복 저장을 막는 unique 키. (인덱스는 @Table에서 선언) */
  @Column(name = "event_id", nullable = false, length = 36)
  private String eventId;

  @Column(name = "aggregate_type", nullable = false, length = 100)
  private String aggregateType; // 이벤트를 발생시킨 애그리거트 타입 (예: "Payment", "Booking")

  /* 애그리거트 식별자. 도메인별로 Long/UUID 등 타입이 혼재할 수 있어 String으로 통일해 저장한다. */
  @Column(name = "aggregate_id", nullable = false, length = 100)
  private String aggregateId;

  @Column(name = "event_type", nullable = false, length = 150)
  private String eventType; // 이벤트 타입명 (DomainEvent.eventName())

  @Column(name = "topic", nullable = false, length = 200)
  private String topic; // 발행 대상 Kafka 토픽

  /* Kafka 파티션 키(DomainEvent.key()). 키가 없는 이벤트도 있어 nullable. */
  @Column(name = "message_key", length = 200)
  private String messageKey;

  @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
  private String payload; // 직렬화된 이벤트 JSON

  @Column(name = "trace_id", length = 64)
  private String traceId; // 분산 추적 ID

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private OutboxStatus status; // 발행 상태. 생성 시 PENDING, 폴링이 SENT/FAILED로 전이

  @Column(name = "retry_count", nullable = false)
  private int retryCount; // 발행 재시도 횟수. 후속 폴링의 재시도 상한 판정에 사용

  @Column(name = "last_error", columnDefinition = "TEXT")
  private String lastError; // 마지막 발행 실패 사유 (디버깅용)

  @Column(name = "published_at")
  private LocalDateTime publishedAt; // Kafka 발행(SENT 전이) 시각. 후속 폴링이 채운다

  @Builder(access = AccessLevel.PRIVATE)
  private OutboxEntity(
      String eventId,
      String aggregateType,
      String aggregateId,
      String eventType,
      String topic,
      String messageKey,
      String payload,
      String traceId,
      OutboxStatus status,
      int retryCount) {
    this.eventId = eventId;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.topic = topic;
    this.messageKey = messageKey;
    this.payload = payload;
    this.traceId = traceId;
    this.status = status;
    this.retryCount = retryCount;
  }

  /**
   * 발행 대기 상태({@link OutboxStatus#PENDING}, retryCount=0)의 Outbox row를 생성한다.
   *
   * <p>{@link DomainEventEnvelope}의 메시징 메타데이터(eventId/eventType/topic/payload/traceId)를 컬럼으로 저장하고,
   * envelope에 없는 애그리거트 정보와 파티션 키는 별도로 받는다.
   *
   * <p>단, envelope의 {@code createdAt}(Instant, 이벤트 생성 시각)은 별도 컬럼으로 보존하지 않고, 같은 트랜잭션에서 채워지는 auditing
   * {@code created_at}(행 저장 시각)으로 근사한다.
   */
  public static OutboxEntity from(
      DomainEventEnvelope envelope, String aggregateType, String aggregateId, String messageKey) {
    return OutboxEntity.builder()
        .eventId(envelope.eventId())
        .aggregateType(aggregateType)
        .aggregateId(aggregateId)
        .eventType(envelope.eventType())
        .topic(envelope.topic())
        .messageKey(messageKey)
        .payload(envelope.payload())
        .traceId(envelope.traceId())
        .status(OutboxStatus.PENDING)
        .retryCount(0)
        .build();
  }

  /**
   * Kafka 발행 성공 시 호출한다. 상태를 {@link OutboxStatus#SENT}로 전이하고 발행 시각을 기록한다.
   *
   * <p>비동기 콜백이 경쟁(중복 dispatch 등)할 수 있어, 비터미널({@link OutboxStatus#PENDING}/{@link
   * OutboxStatus#FAILED}) 상태일 때만 전이한다. 이미 종단 상태면 무시해 상태 역전을 막는다.
   */
  public void markSent(LocalDateTime publishedAt) {
    if (isNotRelayable()) {
      return;
    }
    this.status = OutboxStatus.SENT;
    this.publishedAt = publishedAt;
  }

  /**
   * Kafka 발행 실패 시 호출한다. 재시도 횟수를 증가시키고 마지막 실패 사유를 기록한다. 누적 실패가 {@code maxRetries}에 도달하면 {@link
   * OutboxStatus#DEAD}(재폴링 제외)로, 그 전이면 {@link OutboxStatus#FAILED}(다음 폴링 재시도 대상)로 전이한다.
   *
   * <p>{@link #markSent}와 마찬가지로 비터미널 상태일 때만 전이해, 늦게 도착한 실패 콜백이 이미 SENT 처리된 row를 되돌리지 않도록 한다.
   */
  public void markFailed(String lastError, int maxRetries) {
    if (isNotRelayable()) {
      return;
    }
    this.retryCount++;
    this.status = this.retryCount >= maxRetries ? OutboxStatus.DEAD : OutboxStatus.FAILED;
    this.lastError = lastError;
  }

  /** 종단 상태(SENT/DEAD)면 더는 상태를 전이하지 않는다. */
  private boolean isNotRelayable() {
    return this.status != OutboxStatus.PENDING && this.status != OutboxStatus.FAILED;
  }
}
