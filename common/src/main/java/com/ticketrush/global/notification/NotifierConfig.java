package com.ticketrush.global.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link Notifier} 폴백 등록 설정.
 *
 * <p>{@link SlackNotifier}와 {@link NoOpNotifier}를 상호배타 {@link ConditionalOnProperty}로 조건을 명시해, 두 빈이
 * 동시에 등록되는 상황({@code NoUniqueBeanDefinitionException})을 방지한다(#4).
 *
 * <ul>
 *   <li>{@code app.slack.enabled=true} → {@link SlackNotifier}만 등록
 *   <li>{@code app.slack.enabled=false} 또는 미설정 → {@link NoOpNotifier}만 등록
 * </ul>
 *
 * {@code @ConditionalOnMissingBean}을 사용하면 스캔 순서에 따라 두 빈이 동시에 등록될 수 있어 사용하지 않는다.
 */
@Configuration
public class NotifierConfig {

  @Bean
  @ConditionalOnProperty(
      prefix = "app.slack",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  public NoOpNotifier noOpNotifier() {
    return new NoOpNotifier();
  }
}
