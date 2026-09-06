package com.ticketrush.config;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

  private static final String USER_ID_HEADER = "X-User-Id";

  /*
   * 운영 Gateway 앞의 신뢰 프록시가 Nginx 1개라는 전제.
   * 프록시 홉 수가 달라지면 이 값도 반드시 변경한다.
   */
  private final XForwardedRemoteAddressResolver remoteAddressResolver =
      XForwardedRemoteAddressResolver.maxTrustedIndex(1);

  @Bean
  public KeyResolver userOrIpKeyResolver() {
    return exchange -> {
      String userId = exchange.getRequest().getHeaders().getFirst(USER_ID_HEADER);

      if (StringUtils.hasText(userId)) {
        return Mono.just("user:" + userId);
      }

      InetSocketAddress resolved = remoteAddressResolver.resolve(exchange);

      if (resolved != null && resolved.getAddress() != null) {
        return Mono.just("ip:" + resolved.getAddress().getHostAddress());
      }

      InetSocketAddress direct = exchange.getRequest().getRemoteAddress();

      if (direct != null && direct.getAddress() != null) {
        return Mono.just("ip:" + direct.getAddress().getHostAddress());
      }

      return Mono.empty();
    };
  }
}
