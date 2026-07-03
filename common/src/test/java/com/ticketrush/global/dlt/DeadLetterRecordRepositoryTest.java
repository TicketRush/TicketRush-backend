package com.ticketrush.global.dlt;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.jpa.config.JpaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

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
}
