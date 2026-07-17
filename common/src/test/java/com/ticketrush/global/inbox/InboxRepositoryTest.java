package com.ticketrush.global.inbox;

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

/** {@link JpaConfig}를 임포트해 {@code @EnableJpaAuditing}을 활성화한 뒤 {@code createdAt} 단언을 포함한다. */
@DataJpaTest
@Import(JpaConfig.class)
class InboxRepositoryTest {

  @Autowired private InboxRepository inboxRepository;

  @Test
  @DisplayName("InboxEntity를 저장하면 필드가 보존되고 createdAt이 JPA Auditing으로 기록된다")
  void save_and_find_preserves_fields() {
    // when
    InboxEntity saved =
        inboxRepository.save(InboxEntity.of("seat-group", "evt-1", "BookingCanceledEvent"));
    InboxEntity found = inboxRepository.findById(saved.getId()).orElseThrow();

    // then
    assertThat(found.getConsumerGroup()).isEqualTo("seat-group");
    assertThat(found.getEventId()).isEqualTo("evt-1");
    assertThat(found.getEventType()).isEqualTo("BookingCanceledEvent");
    assertThat(found.getCreatedAt()).isNotNull(); // 처리 시각 = createdAt (processed_at 컬럼 없음)
  }

  @Test
  @DisplayName("같은 (consumer_group, event_id)를 두 번 저장하면 복합 unique 제약 위반이 발생한다")
  void duplicate_group_event_violates_unique() {
    // given
    inboxRepository.saveAndFlush(InboxEntity.of("ticket-group", "evt-dup", "PaymentCanceledEvent"));

    // when & then
    assertThatThrownBy(
            () ->
                inboxRepository.saveAndFlush(
                    InboxEntity.of("ticket-group", "evt-dup", "PaymentCanceledEvent")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("서로 다른 consumer_group은 동일 event_id라도 각각 저장된다(멀티컨슈머 독립 멱등)")
  void same_event_id_different_group_both_saved() {
    // given: PaymentConfirmedEvent를 ticket-group과 booking-group이 같은 eventId로 소비하는 상황
    String eventId = "evt-shared";

    // when
    inboxRepository.saveAndFlush(InboxEntity.of("ticket-group", eventId, "PaymentConfirmed"));
    inboxRepository.saveAndFlush(InboxEntity.of("booking-group", eventId, "PaymentConfirmed"));

    // then: 두 그룹 모두 저장되어 서로의 처리를 막지 않는다
    assertThat(inboxRepository.existsByConsumerGroupAndEventId("ticket-group", eventId)).isTrue();
    assertThat(inboxRepository.existsByConsumerGroupAndEventId("booking-group", eventId)).isTrue();
  }

  @Test
  @DisplayName("existsByConsumerGroupAndEventId는 저장된 (group, eventId)에만 true를 반환한다")
  void existsBy_group_and_event_id() {
    // given
    inboxRepository.saveAndFlush(InboxEntity.of("payment-group", "evt-x", "BookingExpiredEvent"));

    // then
    assertThat(inboxRepository.existsByConsumerGroupAndEventId("payment-group", "evt-x")).isTrue();
    assertThat(inboxRepository.existsByConsumerGroupAndEventId("payment-group", "evt-y")).isFalse();
    assertThat(inboxRepository.existsByConsumerGroupAndEventId("seat-group", "evt-x")).isFalse();
  }

  @Test
  @DisplayName("deleteCreatedBefore는 threshold 이전에 저장된 row만 삭제한다")
  void delete_created_before_threshold() {
    // given
    inboxRepository.saveAndFlush(
        InboxEntity.of("seat-group", "evt-old", "PerformanceCreatedEvent"));

    // when & then: 과거 threshold면 아무것도 삭제되지 않는다
    int deletedByPast = inboxRepository.deleteCreatedBefore(LocalDateTime.now().minusMinutes(1));
    assertThat(deletedByPast).isZero();
    assertThat(inboxRepository.existsByConsumerGroupAndEventId("seat-group", "evt-old")).isTrue();

    // 미래 threshold면 방금 저장된 row가 삭제된다
    int deletedByFuture = inboxRepository.deleteCreatedBefore(LocalDateTime.now().plusMinutes(1));
    assertThat(deletedByFuture).isEqualTo(1);
    assertThat(inboxRepository.existsByConsumerGroupAndEventId("seat-group", "evt-old")).isFalse();
  }
}
