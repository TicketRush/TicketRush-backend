package com.ticketrush.global.dlt;

import com.ticketrush.global.jpa.entity.AutoIdBaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 최대 재시도를 초과해 {@code .DLT}로 발행된 실패 메시지를 영구 저장하는 엔티티.
 *
 * <p>{@code DeadLetterConsumer}가 DLT 헤더/원본 payload에서 값을 뽑아 저장한다. 실패 원인 추적·운영자 조회·수동 복구의 근거가 된다. 이벤트
 * 타입별/원본 토픽별 조회를 위해 {@code (event_type, created_at)}, {@code (original_topic, created_at)} 복합 인덱스를
 * 둔다.
 *
 * <p>저장 시각은 {@link com.ticketrush.global.jpa.entity.BaseTimeEntity}가 관리하는 {@code created_at}(JPA
 * Auditing)으로 기록된다. 별도 {@code occurred_at} 컬럼은 중복이므로 두지 않는다(#9).
 *
 * <p>원본 컨슈머가 {@code DeserializationException}으로 실패한 경우 DLT value가 {@code DomainEventEnvelope}로
 * 역직렬화되지 않을 수 있어, eventType/eventId/payload는 nullable로 두고 최대 보존 저장한다.
 */
@Entity
@Table(
    name = "dead_letter_record",
    indexes = {
      @Index(name = "idx_dlr_event_type_created_at", columnList = "event_type, created_at"),
      @Index(name = "idx_dlr_original_topic_created_at", columnList = "original_topic, created_at")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "id", column = @Column(name = "dead_letter_record_id"))
public class DeadLetterRecord extends AutoIdBaseEntity {

  @Column(name = "original_topic", nullable = false, length = 200)
  private String originalTopic; // 재시도 상한 초과 이전의 원본 토픽(.DLT 접미사 제거)

  @Column(name = "original_partition", nullable = false)
  private int originalPartition; // 원본 파티션

  @Column(name = "original_offset", nullable = false)
  private long originalOffset; // 원본 offset

  /* 파티션 키. 키가 없는 이벤트도 있어 nullable. */
  @Column(name = "message_key", length = 200)
  private String messageKey;

  /* 이벤트 타입명. 역직렬화 실패 케이스에선 없을 수 있어 nullable. */
  @Column(name = "event_type", length = 150)
  private String eventType;

  /* 이벤트 고유 ID(UUID). 역직렬화 실패 케이스에선 없을 수 있어 nullable. */
  @Column(name = "event_id", length = 36)
  private String eventId;

  @Column(name = "payload", columnDefinition = "TEXT")
  private String payload; // 원본 이벤트 JSON 또는 역직렬화 실패 시 raw 문자열

  @Column(name = "exception_fqcn", length = 500)
  private String exceptionFqcn; // 원본 처리 실패 예외 클래스명(DLT 헤더)

  @Column(name = "exception_message", columnDefinition = "TEXT")
  private String exceptionMessage; // 원본 처리 실패 예외 메시지(DLT 헤더, 내부 저장 전용)

  @Builder
  private DeadLetterRecord(
      String originalTopic,
      int originalPartition,
      long originalOffset,
      String messageKey,
      String eventType,
      String eventId,
      String payload,
      String exceptionFqcn,
      String exceptionMessage) {
    this.originalTopic = originalTopic;
    this.originalPartition = originalPartition;
    this.originalOffset = originalOffset;
    this.messageKey = messageKey;
    this.eventType = eventType;
    this.eventId = eventId;
    this.payload = payload;
    this.exceptionFqcn = exceptionFqcn;
    this.exceptionMessage = exceptionMessage;
  }
}
