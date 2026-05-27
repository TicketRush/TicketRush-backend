package com.ticketrush.global.jpa.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
@RequiredArgsConstructor
public class JpaConfig {

  private final EntityManager entityManager;
  private final ObjectProvider<Clock> clockProvider;

  @Bean
  public JPAQueryFactory jpaQueryFactory() {
    return new JPAQueryFactory(entityManager);
  }

  @Bean
  public DateTimeProvider auditingDateTimeProvider() {
    Clock clock = clockProvider.getIfAvailable(Clock::systemDefaultZone);
    return () -> Optional.of(LocalDateTime.now(clock));
  }
}
