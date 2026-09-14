package com.ticketrush.global.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.config.ClockConfig;
import com.ticketrush.global.inbox.InboxEntity;
import com.ticketrush.global.inbox.InboxRepository;
import com.ticketrush.global.jpa.config.JpaConfig;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({JpaConfig.class, UtcAuditingTest.FixedClockConfig.class})
@ResourceLock("java.util.TimeZone.default")
class UtcAuditingTest {

  private static final Instant NOW = Instant.parse("2026-12-31T23:59:59.123456Z");

  @Autowired private InboxRepository repository;
  @Autowired private EntityManager entityManager;

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  @DisplayName("UTC가 아닌 고정 Clock도 JPA createdAt·updatedAt을 UTC로 영속화한다")
  void persisted_auditing_uses_utc_instant(String zone) {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      assertThat(TimeZone.getDefault().getID()).isEqualTo(zone);
      InboxEntity saved = repository.saveAndFlush(InboxEntity.of("utc-group", "evt-utc", "test"));
      entityManager.clear();
      InboxEntity reloaded = repository.findById(saved.getId()).orElseThrow();
      LocalDateTime expected = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
      assertThat(reloaded.getCreatedAt()).isEqualTo(expected);
      assertThat(reloaded.getUpdatedAt()).isEqualTo(expected);
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  @DisplayName("프로덕션 Clock과 Clock 미주입 auditing 대체 경로는 호스트와 무관하게 UTC다")
  void production_clock_and_fallback_use_utc(String zone) {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      assertThat(new ClockConfig().clock().getZone()).isEqualTo(ZoneOffset.UTC);
      DefaultListableBeanFactory emptyFactory = new DefaultListableBeanFactory();
      JpaConfig config = new JpaConfig(entityManager, emptyFactory.getBeanProvider(Clock.class));
      LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
      LocalDateTime actual =
          (LocalDateTime) config.auditingDateTimeProvider().getNow().orElseThrow();
      LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);
      assertThat(actual).isBetween(before, after);
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @TestConfiguration
  static class FixedClockConfig {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    }
  }
}
