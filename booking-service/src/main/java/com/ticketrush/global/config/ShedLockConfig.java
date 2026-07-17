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

  @Value("${spring.application.name:booking-service}")
  private String applicationName;

  private final Environment env;

  public ShedLockConfig(Environment env) {
    this.env = env;
  }

  @Bean
  public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
    String[] activeProfiles = env.getActiveProfiles();
    String profile = activeProfiles.length > 0 ? activeProfiles[0] : "default";
    String keyPrefix = applicationName + "-" + profile;

    return new RedisLockProvider(connectionFactory, keyPrefix);
  }
}
