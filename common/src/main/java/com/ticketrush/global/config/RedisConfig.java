package com.ticketrush.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

// 이 설정은 전 서비스에 컴포넌트 스캔된다. Redis를 안 쓰는 서비스(user/payment/ticket)는
// spring.data.redis.host를 두지 않으므로 이 조건으로 빈 로드를 건너뛰어 불필요한 커넥션을 막는다.
@ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
@Configuration
public class RedisConfig {

  @Bean
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory connectionFactory) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    return container;
  }
}
