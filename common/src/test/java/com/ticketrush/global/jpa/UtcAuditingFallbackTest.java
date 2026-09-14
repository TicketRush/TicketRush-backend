package com.ticketrush.global.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.inbox.InboxEntity;
import com.ticketrush.global.inbox.InboxRepository;
import com.ticketrush.global.jpa.config.JpaConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
class UtcAuditingFallbackTest {

  @Autowired private InboxRepository repository;
  @Autowired private EntityManager entityManager;

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  @DisplayName("Clock 빈이 없는 JPA 대체 경로도 UTC 감사 시각을 영속화한다")
  void fallback_persists_utc_audit_fields(String zone) {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      assertThat(TimeZone.getDefault().getID()).isEqualTo(zone);
      LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusNanos(1_000);
      InboxEntity saved = repository.saveAndFlush(InboxEntity.of("fallback", "evt-utc", "test"));
      LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC).plusNanos(1_000);
      entityManager.clear();
      InboxEntity reloaded = repository.findById(saved.getId()).orElseThrow();
      // H2 TIMESTAMP의 마이크로초 반올림 오차만 경계에 허용한다.
      assertThat(reloaded.getCreatedAt()).isBetween(before, after);
      assertThat(reloaded.getUpdatedAt()).isEqualTo(reloaded.getCreatedAt());
    } finally {
      TimeZone.setDefault(original);
    }
  }
}
