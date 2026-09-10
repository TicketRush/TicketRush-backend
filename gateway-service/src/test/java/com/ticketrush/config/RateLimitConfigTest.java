package com.ticketrush.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class RateLimitConfigTest {

  private final KeyResolver keyResolver = new RateLimitConfig().userOrIpKeyResolver();

  @Test
  @DisplayName("인증 사용자는 X-User-Id를 기준으로 Rate Limit key를 생성한다")
  void 인증_사용자는_userId를_key로_사용한다() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/booking")
                .header("X-User-Id", "42")
                .remoteAddress(new InetSocketAddress("203.0.113.10", 12345))
                .build());

    String key = keyResolver.resolve(exchange).block();

    assertThat(key).isEqualTo("user:42");
  }

  @Test
  @DisplayName("비인증 요청은 클라이언트 IP를 기준으로 Rate Limit key를 생성한다")
  void 비인증_요청은_IP를_key로_사용한다() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("203.0.113.10", 12345))
                .build());

    String key = keyResolver.resolve(exchange).block();

    assertThat(key).isEqualTo("ip:203.0.113.10");
  }

  @Test
  @DisplayName("Nginx 뒤에서는 X-Forwarded-For의 실제 클라이언트 IP를 사용한다")
  void 프록시_환경에서는_XForwardedFor의_클라이언트_IP를_사용한다() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/auth/login")
                .header("X-Forwarded-For", "198.51.100.25")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 54321))
                .build());

    String key = keyResolver.resolve(exchange).block();

    assertThat(key).isEqualTo("ip:198.51.100.25");
  }

  @Test
  @DisplayName("사용자 ID가 있으면 IP보다 사용자 ID를 우선한다")
  void 사용자_ID가_있으면_IP보다_우선한다() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/payment/confirm")
                .header("X-User-Id", "99")
                .header("X-Forwarded-For", "198.51.100.25")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 54321))
                .build());

    String key = keyResolver.resolve(exchange).block();

    assertThat(key).isEqualTo("user:99");
  }
}
