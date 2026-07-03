package com.ticketrush.global.dlt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.global.jpa.config.JpaConfig;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/** {@link JpaConfig}를 임포트해 {@code @EnableJpaAuditing}을 활성화한 뒤 {@code createdAt} 필드 단언을 포함한다. */
@DataJpaTest
@Import(JpaConfig.class)
class DeadLetterRecordRepositoryTest {

  @Autowired private DeadLetterRecordRepository deadLetterRecordRepository;

  @Test
  @DisplayName("DeadLetterRecord를 저장하고 ID로 조회하면 모든 필드가 보존된다")
  void save_and_find_preserves_fields() {
    // given
    DeadLetterRecord record =
        DeadLetterRecord.builder()
            .originalTopic("booking-created-topic")
            .originalPartition(2)
            .originalOffset(123L)
            .messageKey("100")
            .eventType("BookingCreatedEvent")
            .eventId("evt-1")
            .payload("{\"booking_id\":100}")
            .exceptionFqcn("java.lang.IllegalStateException")
            .exceptionMessage("boom")
            .build();
    // 저장 시각은 created_at(JPA Auditing)으로 자동 기록된다. occurredAt 컬럼은 #9에서 제거됨.

    // when
    DeadLetterRecord saved = deadLetterRecordRepository.save(record);
    DeadLetterRecord found = deadLetterRecordRepository.findById(saved.getId()).orElseThrow();

    // then
    assertThat(found.getOriginalTopic()).isEqualTo("booking-created-topic");
    assertThat(found.getOriginalPartition()).isEqualTo(2);
    assertThat(found.getOriginalOffset()).isEqualTo(123L);
    assertThat(found.getMessageKey()).isEqualTo("100");
    assertThat(found.getEventType()).isEqualTo("BookingCreatedEvent");
    assertThat(found.getEventId()).isEqualTo("evt-1");
    assertThat(found.getPayload()).isEqualTo("{\"booking_id\":100}");
    assertThat(found.getExceptionFqcn()).isEqualTo("java.lang.IllegalStateException");
    assertThat(found.getExceptionMessage()).isEqualTo("boom");
    // #9: occurredAt 제거 후 저장시각은 JPA Auditing createdAt으로 관리됨을 검증한다.
    assertThat(found.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("역직렬화 실패 케이스처럼 eventType/eventId가 없어도 저장된다")
  void save_allows_null_event_metadata() {
    // given
    DeadLetterRecord record =
        DeadLetterRecord.builder()
            .originalTopic("some-topic")
            .originalPartition(0)
            .originalOffset(7L)
            .payload("raw-broken-payload")
            .build();

    // when
    DeadLetterRecord saved = deadLetterRecordRepository.save(record);
    DeadLetterRecord found = deadLetterRecordRepository.findById(saved.getId()).orElseThrow();

    // then
    assertThat(found.getEventType()).isNull();
    assertThat(found.getEventId()).isNull();
    assertThat(found.getMessageKey()).isNull();
    assertThat(found.getPayload()).isEqualTo("raw-broken-payload");
  }

  @Test
  @DisplayName("threshold 이전에 저장된 레코드는 deleteCreatedBefore로 삭제된다")
  void deleteCreatedBefore_removes_records_before_threshold() {
    // given: createdAt은 Auditing으로 저장 시점(now)에 자동 세팅된다.
    DeadLetterRecord record =
        DeadLetterRecord.builder()
            .originalTopic("some-topic")
            .originalPartition(0)
            .originalOffset(1L)
            .payload("{\"booking_id\":1}")
            .build();
    DeadLetterRecord saved = deadLetterRecordRepository.saveAndFlush(record);

    // when: 방금 저장분의 createdAt보다 미래(now+1분) threshold로 삭제
    int deleted =
        deadLetterRecordRepository.deleteCreatedBefore(LocalDateTime.now().plusMinutes(1));

    // then
    assertThat(deleted).isEqualTo(1);
    assertThat(deadLetterRecordRepository.findById(saved.getId())).isEmpty();
  }

  @Test
  @DisplayName("threshold 이후에 저장된 레코드는 deleteCreatedBefore로 삭제되지 않는다")
  void deleteCreatedBefore_keeps_records_after_threshold() {
    // given
    DeadLetterRecord record =
        DeadLetterRecord.builder()
            .originalTopic("some-topic")
            .originalPartition(0)
            .originalOffset(2L)
            .payload("{\"booking_id\":2}")
            .build();
    DeadLetterRecord saved = deadLetterRecordRepository.saveAndFlush(record);

    // when: 방금 저장분의 createdAt보다 과거(now-1년) threshold로는 삭제되지 않아야 한다.
    int deleted = deadLetterRecordRepository.deleteCreatedBefore(LocalDateTime.now().minusYears(1));

    // then
    assertThat(deleted).isZero();
    assertThat(deadLetterRecordRepository.findById(saved.getId())).isPresent();
  }

  @Test
  @DisplayName(
      "동일 (originalTopic, originalPartition, originalOffset)로 두 번 저장하면 멱등 제약 위반 예외가 발생한다(#308)")
  void duplicate_topic_partition_offset_violates_unique_constraint() {
    // given: 동일 좌표(booking-created-topic, 2, 123)를 가진 두 레코드
    deadLetterRecordRepository.saveAndFlush(
        DeadLetterRecord.builder()
            .originalTopic("booking-created-topic")
            .originalPartition(2)
            .originalOffset(123L)
            .payload("{\"booking_id\":100}")
            .build());

    DeadLetterRecord duplicate =
        DeadLetterRecord.builder()
            .originalTopic("booking-created-topic")
            .originalPartition(2)
            .originalOffset(123L)
            .payload("{\"booking_id\":100}")
            .build();

    // when & then: 두 번째 저장(flush)에서 unique 제약 위반이 발생한다.
    assertThatThrownBy(() -> deadLetterRecordRepository.saveAndFlush(duplicate))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("좌표 중 하나라도 다르면 동일 토픽이어도 정상 저장된다(#308)")
  void different_offset_is_saved_normally() {
    // given
    deadLetterRecordRepository.saveAndFlush(
        DeadLetterRecord.builder()
            .originalTopic("booking-created-topic")
            .originalPartition(2)
            .originalOffset(123L)
            .payload("{\"booking_id\":100}")
            .build());

    // when: offset만 다른 레코드
    DeadLetterRecord other =
        deadLetterRecordRepository.saveAndFlush(
            DeadLetterRecord.builder()
                .originalTopic("booking-created-topic")
                .originalPartition(2)
                .originalOffset(124L)
                .payload("{\"booking_id\":101}")
                .build());

    // then
    assertThat(deadLetterRecordRepository.findById(other.getId())).isPresent();
  }

  @Test
  @DisplayName("저장된 좌표엔 existsBy=true, 없는 좌표엔 false를 반환한다(#308)")
  void existsByOriginalTopicAndOriginalPartitionAndOriginalOffset_returns_correct_result() {
    // given
    deadLetterRecordRepository.saveAndFlush(
        DeadLetterRecord.builder()
            .originalTopic("booking-created-topic")
            .originalPartition(2)
            .originalOffset(123L)
            .payload("{\"booking_id\":100}")
            .build());

    // when & then: 동일 좌표 → true
    assertThat(
            deadLetterRecordRepository.existsByOriginalTopicAndOriginalPartitionAndOriginalOffset(
                "booking-created-topic", 2, 123L))
        .isTrue();

    // 다른 offset → false
    assertThat(
            deadLetterRecordRepository.existsByOriginalTopicAndOriginalPartitionAndOriginalOffset(
                "booking-created-topic", 2, 999L))
        .isFalse();
  }
}
