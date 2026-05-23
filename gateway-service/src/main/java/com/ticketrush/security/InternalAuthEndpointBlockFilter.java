package com.ticketrush.security;

import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class InternalAuthEndpointBlockFilter implements GlobalFilter, Ordered {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

    String path = exchange.getRequest().getURI().getPath();
    HttpMethod method = exchange.getRequest().getMethod();

    boolean isInternalAuthEndpoint =
        (HttpMethod.GET.equals(method)
                && "/api/v1/auth/signup/email-verification/verified".equals(path))
            || (HttpMethod.POST.equals(method)
                && "/api/v1/auth/signup/email-verification/consume".equals(path));

    if (!isInternalAuthEndpoint) {
      return chain.filter(exchange);
    }

    log.warn("[Gateway] 내부용 인증 API 외부 접근 차단 method={}, path={}", method, path);

    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);

    byte[] bytes = "Forbidden internal endpoint".getBytes(StandardCharsets.UTF_8);
    return exchange
        .getResponse()
        .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
  }

  @Override
  public int getOrder() {
    return -2;
  }
}
