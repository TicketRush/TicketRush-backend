package com.ticketrush.security;

import com.ticketrush.exception.BusinessException;
import com.ticketrush.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

  private static final String USER_ID_HEADER = "X-User-Id";
  private static final String USER_ROLE_HEADER = "X-User-Role";
  private static final String GATEWAY_TOKEN_HEADER = "X-Gateway-Token";
  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private final JwtTokenProvider jwtTokenProvider;

  @Value("${gateway.internal-token}")
  private String gatewayToken;

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

    // 요청당 로그를 남기지 않는다. 이 필터는 모든 라우트가 지나므로 INFO 한 줄이 곧 전 트래픽의 디스크·CPU
    // 비용이고, #403이 로그 폭주로 이미 한 번 겪은 실패 모드다(ADR 0009 §4). 검증 실패는 JwtTokenProvider가
    // WARN으로 남긴다.
    String token = resolveToken(exchange);

    ServerHttpRequest.Builder requestBuilder =
        exchange
            .getRequest()
            .mutate()
            .headers(
                headers -> {
                  // 외부 사용자가 신뢰 헤더를 임의로 전달하지 못하도록 모두 제거한다.
                  headers.remove(USER_ID_HEADER);
                  headers.remove(USER_ROLE_HEADER);
                  headers.remove(GATEWAY_TOKEN_HEADER);
                  headers.remove(INTERNAL_TOKEN_HEADER);
                });

    if (token == null) {
      ServerHttpRequest request = requestBuilder.build();
      return chain.filter(exchange.mutate().request(request).build());
    }

    boolean validToken = jwtTokenProvider.validateToken(token);

    if (!validToken) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_TOKEN);
    }

    String type = jwtTokenProvider.getType(token);

    if (!"access".equals(type)) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_TOKEN_TYPE);
    }

    Long userId = jwtTokenProvider.getUserId(token);
    String role = jwtTokenProvider.getRole(token);

    ServerHttpRequest request =
        requestBuilder
            .headers(
                headers -> {
                  headers.set(USER_ID_HEADER, String.valueOf(userId));
                  headers.set(USER_ROLE_HEADER, role);
                  headers.set(GATEWAY_TOKEN_HEADER, gatewayToken);
                })
            .build();

    return chain.filter(exchange.mutate().request(request).build());
  }

  private String resolveToken(ServerWebExchange exchange) {

    String bearer = exchange.getRequest().getHeaders().getFirst("Authorization");

    if (bearer != null && bearer.startsWith("Bearer ")) {
      return bearer.substring(7);
    }

    return null;
  }

  @Override
  public int getOrder() {
    return -1;
  }
}
