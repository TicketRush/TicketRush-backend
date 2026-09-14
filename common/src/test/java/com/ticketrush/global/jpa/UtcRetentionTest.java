package com.ticketrush.global.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.dlt.DeadLetterRecord;
import com.ticketrush.global.dlt.DeadLetterRecordRepository;
import com.ticketrush.global.dlt.DltMonitorProperties;
import com.ticketrush.global.dlt.DltRetentionService;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.inbox.InboxEntity;
import com.ticketrush.global.inbox.InboxRepository;
import com.ticketrush.global.inbox.InboxRetentionBatchDeleter;
import com.ticketrush.global.inbox.InboxRetentionProperties;
import com.ticketrush.global.inbox.InboxRetentionService;
import com.ticketrush.global.jpa.config.JpaConfig;
import com.ticketrush.global.outbox.OutboxEntity;
import com.ticketrush.global.outbox.OutboxProperties;
import com.ticketrush.global.outbox.OutboxRepository;
import com.ticketrush.global.outbox.OutboxRetentionService;
import com.ticketrush.global.outbox.OutboxStatusTransition;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(JpaConfig.class)
@ResourceLock("java.util.TimeZone.default")
class UtcRetentionTest {

  @Autowired private InboxRepository inboxRepository;
  @Autowired private DeadLetterRecordRepository dltRepository;
  @Autowired private OutboxRepository outboxRepository;
  @Autowired private EntityManager entityManager;

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  @DisplayName("Inbox UTC 보존 기준 양쪽의 기존 감사 행을 삭제·보존한다")
  void inbox_retention_uses_utc_cutoff(String zone) {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(30);
      InboxEntity old = inboxRepository.saveAndFlush(InboxEntity.of("group", "old", "test"));
      InboxEntity recent = inboxRepository.saveAndFlush(InboxEntity.of("group", "recent", "test"));
      setKnownUtcCreatedAt("InboxEntity", old.getId(), cutoff.minusMinutes(1));
      setKnownUtcCreatedAt("InboxEntity", recent.getId(), cutoff.plusMinutes(1));
      InboxRetentionService service =
          new InboxRetentionService(
              new InboxRetentionBatchDeleter(inboxRepository), new InboxRetentionProperties());

      assertThat(service.purgeExpired()).isEqualTo(1);
      assertThat(inboxRepository.existsById(old.getId())).isFalse();
      assertThat(inboxRepository.existsById(recent.getId())).isTrue();
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  @DisplayName("DLT UTC 보존 기준 양쪽의 기존 감사 행을 삭제·보존한다")
  void dlt_retention_uses_utc_cutoff(String zone) {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      DltMonitorProperties properties = new DltMonitorProperties();
      properties.setRetentionDays(30);
      LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(30);
      DeadLetterRecord old = dltRepository.saveAndFlush(dltRow("old", 1L));
      DeadLetterRecord recent = dltRepository.saveAndFlush(dltRow("recent", 2L));
      setKnownUtcCreatedAt("DeadLetterRecord", old.getId(), cutoff.minusMinutes(1));
      setKnownUtcCreatedAt("DeadLetterRecord", recent.getId(), cutoff.plusMinutes(1));

      assertThat(new DltRetentionService(dltRepository, properties).purgeExpired()).isEqualTo(1);
      assertThat(dltRepository.existsById(old.getId())).isFalse();
      assertThat(dltRepository.existsById(recent.getId())).isTrue();
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  @DisplayName("Outbox publishedAt 생성과 보존 기간 비교가 같은 UTC 기준을 사용한다")
  void outbox_publication_and_retention_share_utc(String zone) {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      OutboxProperties properties = new OutboxProperties();
      properties.setAggregateTypes(List.of("Booking"));
      OutboxEntity recent = outboxRepository.saveAndFlush(outboxRow("recent"));
      LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
      new OutboxStatusTransition(outboxRepository, properties).markSuccess(recent.getId());
      LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);
      assertThat(recent.getPublishedAt()).isBetween(before, after);
      LocalDateTime cutoff = before.minusHours(properties.getRetentionHours());
      OutboxEntity old = outboxRow("old");
      old.markSent(cutoff.minusMinutes(1));
      outboxRepository.saveAndFlush(old);
      OutboxEntity retained = outboxRow("retained");
      retained.markSent(cutoff.plusMinutes(1));
      outboxRepository.saveAndFlush(retained);

      assertThat(new OutboxRetentionService(outboxRepository, properties).purgeExpiredSent())
          .isEqualTo(1);
      assertThat(outboxRepository.existsById(old.getId())).isFalse();
      assertThat(outboxRepository.existsById(retained.getId())).isTrue();
      assertThat(outboxRepository.existsById(recent.getId())).isTrue();
    } finally {
      TimeZone.setDefault(original);
    }
  }

  private void setKnownUtcCreatedAt(String entityName, Long id, LocalDateTime knownUtc) {
    // UTC 출처가 확인된 과거 감사 행을 구성한다. 미확인 운영 데이터의 보정을 뜻하지 않는다.
    entityManager
        .createQuery("update " + entityName + " e set e.createdAt = :time where e.id = :id")
        .setParameter("time", knownUtc)
        .setParameter("id", id)
        .executeUpdate();
    entityManager.clear();
  }

  private DeadLetterRecord dltRow(String eventId, long offset) {
    return DeadLetterRecord.builder()
        .originalTopic("test-topic")
        .originalPartition(0)
        .originalOffset(offset)
        .eventId(eventId)
        .payload("{}")
        .build();
  }

  private OutboxEntity outboxRow(String eventId) {
    return OutboxEntity.from(
        new DomainEventEnvelope(eventId, "test", Instant.now(), "test-topic", "{}", "trace"),
        "Booking",
        "1",
        "1");
  }
}
