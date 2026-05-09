package com.ticketrush.security;

import com.ticketrush.exception.BusinessException;
import com.ticketrush.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

  private final JwtTokenProvider jwtTokenProvider;

  @Value("${gateway.internal-token}")
  private String internalToken;

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

    log.info("🔥 JwtAuthenticationFilter 실행");

    String token = resolveToken(exchange);

    // 토큰 없으면 그냥 통과
    if (token == null) {
      return chain.filter(exchange);
    }

    // JWT 검증
    jwtTokenProvider.validateToken(token);

    // AccessToken 여부 검증
    String type = jwtTokenProvider.getType(token);

    if (!"access".equals(type)) {
      throw new BusinessException(ErrorStatus.AUTH_INVALID_TOKEN_TYPE);
    }

    // 토큰에서 사용자 정보 추출
    Long userId = jwtTokenProvider.getUserId(token);
    String role = jwtTokenProvider.getRole(token);

    log.info("🔥 userId = {}", userId);
    log.info("🔥 role = {}", role);

    // 내부 서비스로 사용자 정보 전달
    ServerHttpRequest request =
        exchange
            .getRequest()
            .mutate()
            .headers(
                headers -> {

                  // 외부 헤더 제거
                  headers.remove("X-User-Id");
                  headers.remove("X-User-Role");
                  headers.remove("X-Internal-Token");

                  // Gateway가 다시 세팅
                  headers.set("X-User-Id", String.valueOf(userId));
                  headers.set("X-User-Role", role);
                  headers.set("X-Internal-Token", internalToken);
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
