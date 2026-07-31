package com.ticketrush.queue;

import com.ticketrush.dto.response.ApiResponse;
import com.ticketrush.status.ErrorStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * 예매 경로 입장 토큰 게이트(ADR 0009 §2).
 *
 * <p>"예매 API는 유효 입장 토큰만 통과시킨다 — 그 결과 예매 경로의 부하가 유입 규모와 무관하게 입장 허용량으로 고정된다. <b>이것이 이 설계의 전부다.</b>"
 *
 * <p>booking-service가 아니라 게이트웨이에서 막는다. 홉 수는 같지만 <b>차단 지점</b>이 다르다 — 다운스트림에서 막으면 거절될 요청까지
 * booking-service에 도달해 위 목적이 무너진다.
 *
 * <p>order 0 — {@code JwtAuthenticationFilter}(-1) <b>뒤</b>여야 그 필터가 주입한 {@code X-User-Id} 와 토큰 소유자를
 * 대조할 수 있다. 예매 경로가 아닌 요청은 문자열 비교 한 번으로 즉시 통과하므로 나머지 7개 라우트에 실질 비용이 없다.
 *
 * <p>검증은 Redis GET 한 번이고 서명 연산이 없다. 입장 토큰을 JWT로 발급하지 않은 이유가 이것이다(ADR 0009 기각안 4) — CPU가 이미 벽인 곳에서
 * 예매 요청마다 서명 검증 비용을 새로 만들 이유가 없다.
 */
@Slf4j
@Component
public class EntryTokenGatewayFilter implements GlobalFilter, Ordered {

  /**
   * 라우트 술어와 <b>같은 매칭 원시타입</b>을 쓴다.
   *
   * <p>문자열 비교({@code request.getPath().value().equals(...)})로 두면 게이트가 뚫린다. {@code
   * getPath().value()} 는 디코딩 전 원시 경로인데, 라우트 술어({@code Path=/api/v1/booking/**})와 다운스트림
   * {@code @PostMapping} 은 둘 다 <b>디코딩되고 matrix 파라미터가 제거된 세그먼트</b>로 매칭한다. 그래서 {@code POST
   * /api/v1/booking;x=1} 이나 {@code /api/v1/%62ooking} 은 라우팅은 되면서 게이트만 건너뛴다 — 스크립트를 쓰는 사람은 대기열을
   * 무시하고, 정직한 사용자만 줄을 선다.
   */
  private static final PathPattern BOOKING_PATTERN =
      new PathPatternParser().parse("/api/v1/booking");

  private static final String ENTRY_TOKEN_HEADER = "X-Entry-Token";
  private static final String USER_ID_HEADER = "X-User-Id";

  private final ReactiveStringRedisTemplate redis;
  private final WaitingRoomProperties properties;
  private final WaitingRoomMetrics metrics;

  /**
   * 거절 응답은 기동 시 직렬화해 둔다.
   *
   * <p>1만 명이 몰리는 오픈 시각에 거절 경로가 가장 뜨거울 수 있는데, 그 자리에서 Jackson을 부르면 막으려던 부하를 거절하면서 다시 만들어 낸다.
   *
   * <p>손으로 JSON 문자열을 쓰지 않고 실제 {@link ApiResponse} 를 주입된 매퍼로 직렬화한다. 앱 전역이 snake_case({@code
   * JacksonConfig})라 손으로 쓰면 이 두 응답만 필드명이 달라지고, 봉투에 필드가 추가돼도 여기만 조용히 뒤처진다.
   */
  private final byte[] entryTokenRequiredBody;

  private final byte[] unavailableBody;

  public EntryTokenGatewayFilter(
      ReactiveStringRedisTemplate redis,
      WaitingRoomProperties properties,
      WaitingRoomMetrics metrics,
      ObjectMapper objectMapper) {
    this.redis = redis;
    this.properties = properties;
    this.metrics = metrics;
    this.entryTokenRequiredBody = serialize(objectMapper, ErrorStatus.QUEUE_ENTRY_TOKEN_REQUIRED);
    this.unavailableBody = serialize(objectMapper, ErrorStatus.QUEUE_UNAVAILABLE);
  }

  private static byte[] serialize(ObjectMapper objectMapper, ErrorStatus status) {
    ApiResponse<Void> body =
        new ApiResponse<>(false, status.getCode(), status.getMessage(), null, null);
    return objectMapper.writeValueAsBytes(body);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

    if (!properties.enabled() || !isBookingRequest(exchange.getRequest())) {
      return chain.filter(exchange);
    }

    String entryToken = exchange.getRequest().getHeaders().getFirst(ENTRY_TOKEN_HEADER);
    if (entryToken == null || entryToken.isBlank()) {
      metrics.recordEntryTokenMissing();
      return reject(exchange, ErrorStatus.QUEUE_ENTRY_TOKEN_REQUIRED, entryTokenRequiredBody);
    }

    String userId = exchange.getRequest().getHeaders().getFirst(USER_ID_HEADER);

    return redis
        .opsForValue()
        .get(WaitingRoomKey.entryToken(entryToken))
        // 남의 토큰과 없는 토큰을 코드로 구분하지 않는다 — 유효한 토큰을 탐색할 단서를 주지 않기 위해서다.
        .map(owner -> owner.equals(userId) ? Verdict.VALID : Verdict.INVALID)
        .defaultIfEmpty(Verdict.INVALID)
        .onErrorResume(
            e -> {
              log.warn("입장 토큰 확인 실패 — fail-closed로 예매를 거절한다(ADR 0008).", e);
              return Mono.just(Verdict.UNAVAILABLE);
            })
        .flatMap(verdict -> apply(verdict, exchange, chain));
  }

  private Mono<Void> apply(Verdict verdict, ServerWebExchange exchange, GatewayFilterChain chain) {
    return switch (verdict) {
      case VALID -> {
        metrics.recordEntryTokenValid();
        yield chain.filter(exchange);
      }
      case INVALID -> {
        metrics.recordEntryTokenInvalid();
        yield reject(exchange, ErrorStatus.QUEUE_ENTRY_TOKEN_REQUIRED, entryTokenRequiredBody);
      }
      case UNAVAILABLE -> {
        metrics.recordEntryTokenUnavailable();
        yield reject(exchange, ErrorStatus.QUEUE_UNAVAILABLE, unavailableBody);
      }
    };
  }

  private static boolean isBookingRequest(ServerHttpRequest request) {
    return HttpMethod.POST.equals(request.getMethod())
        && BOOKING_PATTERN.matches(request.getPath().pathWithinApplication());
  }

  private static Mono<Void> reject(
      ServerWebExchange exchange, ErrorStatus status, byte[] serializedBody) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status.getHttpStatus());
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(serializedBody)));
  }

  @Override
  public int getOrder() {
    // JwtAuthenticationFilter(-1)가 X-User-Id를 주입한 뒤에 돈다.
    return 0;
  }

  private enum Verdict {
    VALID,
    INVALID,
    UNAVAILABLE
  }
}
