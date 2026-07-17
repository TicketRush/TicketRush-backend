package com.ticketrush.global.inbox;

import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 수신 이벤트의 중복 처리를 막는 Inbox 패턴 엔티티 (#110).
 *
 * <p>at-least-once delivery 환경에서 동일 이벤트가 여러 번 전달되어도 한 번만 처리되도록, 처리 성공 시 {@code (consumer_group,
 * event_id)}를 이 테이블에 기록한다. {@code InboxService.runIfFirst}가 비즈니스 로직과 <b>동일 트랜잭션</b>에서 이 row를 저장해
 * "처리"와 "기록"의 원자성을 보장한다.
 *
 * <p><b>복합 키 {@code (consumer_group, event_id)}</b>: 하나의 이벤트를 여러 컨슈머 그룹이 소비할 수 있고(예: {@code
 * PaymentConfirmedEvent}를 ticket-group·booking-group이 모두 소비), 로컬은 전 서비스가 단일 스키마를 공유한다. {@code
 * event_id} 단독 unique이면 한 그룹이 처리한 뒤 다른 그룹이 중복으로 오인해 스킵되므로, 컨슈머 그룹까지 묶어 그룹별로 독립 멱등을 보장한다.
 *
 * <p>처리 시각은 {@link com.ticketrush.global.jpa.entity.BaseTimeEntity}가 관리하는 {@code created_at}(JPA
 * Auditing)으로 기록된다. row는 처리 시점에 저장되므로 별도 {@code processed_at} 컬럼은 중복이라 두지 않는다(#9·Outbox·DLT와 동일
 * 컨벤션).
 *
 * <p>retention 범위 삭제({@code created_at} 기준)를 위해 {@code (created_at)} 단독 인덱스를 둔다.
 */
@Entity
@Table(
    name = "inbox",
    indexes = {@Index(name = "idx_inbox_created_at", columnList = "created_at")},
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_inbox_group_event",
          columnNames = {"consumer_group", "event_id"})
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "inbox_id"))
public class InboxEntity extends AutoIdBaseEntity {

  @Column(name = "consumer_group", nullable = false, length = 100)
  private String consumerGroup; // 이벤트를 처리한 컨슈머 그룹 (KafkaConsumerGroup 상수)

  @Column(name = "event_id", nullable = false, length = 36)
  private String eventId; // DomainEventEnvelope.eventId (UUID). 멱등 식별자

  @Column(name = "event_type", length = 150)
  private String eventType; // 이벤트 타입명. 관측/디버깅용이라 nullable

  @Builder(access = AccessLevel.PRIVATE)
  private InboxEntity(String consumerGroup, String eventId, String eventType) {
    this.consumerGroup = consumerGroup;
    this.eventId = eventId;
    this.eventType = eventType;
  }

  /** 처리 완료를 기록할 Inbox row를 생성한다. */
  public static InboxEntity of(String consumerGroup, String eventId, String eventType) {
    return InboxEntity.builder()
        .consumerGroup(consumerGroup)
        .eventId(eventId)
        .eventType(eventType)
        .build();
  }
}
