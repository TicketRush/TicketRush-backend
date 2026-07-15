package com.ticketrush.global.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "50s")
public class ShedLockConfig {

  @Value("${spring.application.name:performance-service}")
  private String applicationName;

  private final Environment env;

  // Environment Bean 주입
  public ShedLockConfig(Environment env) {
    this.env = env;
  }

  @Bean
  public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
    String[] activeProfiles = env.getActiveProfiles();

    // 활성화된 프로파일이 있으면 첫 번째 값을, 없으면 "default"를 사용
    String profile = activeProfiles.length > 0 ? activeProfiles[0] : "default";

    // "performance-service-local" 형태로 조합됨
    String keyPrefix = applicationName + "-" + profile;

    return new RedisLockProvider(connectionFactory, keyPrefix);
  }
}
