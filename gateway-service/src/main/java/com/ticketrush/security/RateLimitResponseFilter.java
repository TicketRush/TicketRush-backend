package com.ticketrush.security;

import com.ticketrush.dto.response.ApiResponse;
import com.ticketrush.status.ErrorStatus;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * RequestRateLimiter가 제한 초과 시 생성하는 빈 429 응답을 팀 공통 ApiResponse 형식의 JSON 응답으로 변환한다.
 *
 * <p>Spring Cloud Gateway 5.0.0의 RequestRateLimiter는 제한 초과 시 HTTP 429를 설정한 뒤
 * response.setComplete()만 호출하므로 body가 없다. 응답을 미리 decorate하여 Rate Limit에 의해 라우팅 전에 종료되는 429만 가로챈다.
 *
 * <p>다운스트림 서비스가 자체적으로 반환하는 429 응답은 변경하지 않는다.
 */
@Component
public class RateLimitResponseFilter implements GlobalFilter, Ordered {

  private static final String RATE_LIMIT_ROUTE_SUFFIX = "-rate-limit";

  /**
   * Rate Limit 초과 응답은 기동 시 한 번만 직렬화한다.
   *
   * <p>제한 초과 요청이 몰리는 상황에서 요청마다 Jackson 직렬화를 수행하지 않도록 한다. 기존 EntryTokenGatewayFilter와 동일한 방식이며, 앱의
   * ObjectMapper를 사용하므로 snake_case 등 전역 Jackson 설정도 그대로 적용된다.
   */
  private final byte[] tooManyRequestsBody;

  public RateLimitResponseFilter(ObjectMapper objectMapper) {
    ErrorStatus status = ErrorStatus.TOO_MANY_REQUESTS;

    ApiResponse<Void> body =
        new ApiResponse<>(false, status.getCode(), status.getMessage(), null, null);

    this.tooManyRequestsBody = objectMapper.writeValueAsBytes(body);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

    ServerHttpResponse originalResponse = exchange.getResponse();

    ServerHttpResponseDecorator decoratedResponse =
        new ServerHttpResponseDecorator(originalResponse) {

          @Override
          public Mono<Void> setComplete() {

            if (!isRateLimitRejected(exchange, this)) {
              return super.setComplete();
            }

            getHeaders().setContentType(MediaType.APPLICATION_JSON);

            return super.writeWith(Mono.just(bufferFactory().wrap(tooManyRequestsBody)));
          }
        };

    return chain.filter(exchange.mutate().response(decoratedResponse).build());
  }

  private static boolean isRateLimitRejected(
      ServerWebExchange exchange, ServerHttpResponse response) {

    if (!HttpStatus.TOO_MANY_REQUESTS.equals(response.getStatusCode())) {
      return false;
    }

    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);

    if (route == null || !route.getId().endsWith(RATE_LIMIT_ROUTE_SUFFIX)) {
      return false;
    }

    /*
     * RequestRateLimiter는 실제 downstream routing 전에 요청을 차단한다.
     *
     * 따라서 이미 routing된 요청에서 downstream 서비스가 자체적으로 반환한
     * 429는 건드리지 않는다.
     */
    return !ServerWebExchangeUtils.isAlreadyRouted(exchange);
  }

  @Override
  public int getOrder() {
    /*
     * 응답이 완료되기 전에 response decorator를 설치해야 한다.
     * JwtAuthenticationFilter(-1)와 Gateway response write 단계보다 앞에서 실행한다.
     */
    return -2;
  }
}
