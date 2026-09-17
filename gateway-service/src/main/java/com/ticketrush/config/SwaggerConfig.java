package com.ticketrush.config;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json31;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 게이트웨이 자신의 API 문서도 실제 계약대로 내보낸다 (#658).
 *
 * <p>다른 서비스는 {@code common} 의 같은 빈이 처리하지만 이 서비스만 {@code common} 을 의존하지 않아 따로 둔다. 각 서비스 문서는 여기를 그냥
 * 지나가므로(RewritePath 중계), 이 설정이 관여하는 것은 대기열 API 스키마뿐이다.
 */
@Configuration
public class SwaggerConfig {

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public ModelResolver snakeCaseModelResolver() {
    // 이 우선순위는 체인의 "맨 뒤"를 뜻한다 — swagger-core 가 컨버터를 목록 맨 앞에 끼워 넣어서,
    // 먼저 오는 빈일수록 체인에서는 뒤로 밀린다. common 쪽 같은 이름의 빈과 함께 고칠 것.
    return new SnakeCaseModelResolver(Json31.mapper()).openapi31(true);
  }
}
