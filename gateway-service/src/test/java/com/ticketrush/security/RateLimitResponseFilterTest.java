package com.ticketrush.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketrush.dto.response.ApiResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tools.jackson.databind.ObjectMapper;

class RateLimitResponseFilterTest {

  private static final String TOO_MANY_REQUESTS_JSON =
      "{\"is_success\":false,"
          + "\"code\":\"COMMON_429\","
          + "\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\"}";

  private RateLimitResponseFilter filter;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = mock(ObjectMapper.class);

    when(objectMapper.writeValueAsBytes(any(ApiResponse.class)))
        .thenReturn(TOO_MANY_REQUESTS_JSON.getBytes(StandardCharsets.UTF_8));

    filter = new RateLimitResponseFilter(objectMapper);
  }

  @Test
  @DisplayName("Rate Limit route에서 발생한 빈 429를 ApiResponse JSON으로 변환한다")
  void rateLimit_429를_ApiResponse로_변환한다() {
    MockServerWebExchange exchange = exchangeWithRoute("payment-confirm-rate-limit");

    GatewayFilterChain chain =
        currentExchange -> {
          currentExchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
          return currentExchange.getResponse().setComplete();
        };

    filter.filter(exchange, chain).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    assertThat(exchange.getResponse().getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_JSON);

    assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(TOO_MANY_REQUESTS_JSON);
  }

  @Test
  @DisplayName("일반 route가 반환한 429는 Gateway가 덮어쓰지 않는다")
  void 일반_route의_429는_변경하지_않는다() {
    MockServerWebExchange exchange = exchangeWithRoute("payment-service");

    GatewayFilterChain chain =
        currentExchange -> {
          currentExchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
          return currentExchange.getResponse().setComplete();
        };

    filter.filter(exchange, chain).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    assertThat(exchange.getResponse().getBodyAsString().block()).isEmpty();
  }

  @Test
  @DisplayName("Rate Limit route라도 이미 downstream으로 라우팅된 429는 덮어쓰지 않는다")
  void downstream에서_발생한_429는_변경하지_않는다() {
    MockServerWebExchange exchange = exchangeWithRoute("payment-confirm-rate-limit");

    ServerWebExchangeUtils.setAlreadyRouted(exchange);

    GatewayFilterChain chain =
        currentExchange -> {
          currentExchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
          return currentExchange.getResponse().setComplete();
        };

    filter.filter(exchange, chain).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    assertThat(exchange.getResponse().getBodyAsString().block()).isEmpty();
  }

  @Test
  @DisplayName("정상 응답은 Rate Limit 응답 필터가 변경하지 않는다")
  void 정상_응답은_변경하지_않는다() {
    MockServerWebExchange exchange = exchangeWithRoute("booking-create-rate-limit");

    GatewayFilterChain chain =
        currentExchange -> {
          currentExchange.getResponse().setStatusCode(HttpStatus.OK);
          return currentExchange.getResponse().setComplete();
        };

    filter.filter(exchange, chain).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(exchange.getResponse().getBodyAsString().block()).isEmpty();
  }

  @Test
  @DisplayName("JWT 필터보다 먼저 response decorator를 설치한다")
  void Jwt_필터보다_먼저_실행된다() {
    assertThat(filter.getOrder()).isLessThan(-1);
  }

  private static MockServerWebExchange exchangeWithRoute(String routeId) {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

    Route route =
        Route.async()
            .id(routeId)
            .uri("http://localhost:9999")
            .predicate(serverWebExchange -> true)
            .build();

    exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);

    return exchange;
  }
}
