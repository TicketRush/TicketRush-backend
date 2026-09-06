package com.ticketrush.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "jwt.secret=test-secret-key-for-rate-limit-integration-test",
      "queue.enabled=false",

      // 실제 서비스 없이 Gateway + RequestRateLimiter 전체 체인을 검증하기 위한 테스트 전용 route.
      "spring.cloud.gateway.server.webflux.routes[0].id=" + "rate-limit-probe-rate-limit",
      "spring.cloud.gateway.server.webflux.routes[0].uri=" + "forward:///__rate-limit-ok",
      "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=" + "Path=/__rate-limit-probe",
      "spring.cloud.gateway.server.webflux.routes[0].filters[0].name=" + "RequestRateLimiter",
      "spring.cloud.gateway.server.webflux.routes[0].filters[0].args.key-resolver="
          + "#{@userOrIpKeyResolver}",

      // 1회/분 제한.
      // 첫 요청은 허용되고 바로 이어지는 두 번째 요청은 429가 된다.
      "spring.cloud.gateway.server.webflux.routes[0].filters[0].args."
          + "redis-rate-limiter.replenishRate=1",
      "spring.cloud.gateway.server.webflux.routes[0].filters[0].args."
          + "redis-rate-limiter.burstCapacity=60",
      "spring.cloud.gateway.server.webflux.routes[0].filters[0].args."
          + "redis-rate-limiter.requestedTokens=60"
    })
@Testcontainers
@Import(RateLimitGatewayTest.RateLimitProbeController.class)
class RateLimitGatewayTest {

  private static final String ROUTE_ID = "rate-limit-probe-rate-limit";

  /** 운영 및 기존 WaitingRoomGatewayTest와 동일한 Redis 이미지. */
  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @LocalServerPort private int port;

  @Autowired private JwtTokenProvider jwtTokenProvider;

  @Autowired private ReactiveStringRedisTemplate redisTemplate;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(10))
            .build();

    // 테스트별 Rate Limit bucket 격리.
    redisTemplate
        .keys("request_rate_limiter.*")
        .flatMap(redisTemplate::delete)
        .then()
        .block(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("같은 IP가 임계치를 초과하면 429와 팀 ApiResponse 형식을 반환한다")
  void 같은_IP가_임계치를_초과하면_429를_반환한다() {
    String clientIp = "198.51.100.10";

    requestWithIp(clientIp).expectStatus().isOk().expectBody(String.class).isEqualTo("ok");

    requestWithIp(clientIp)
        .expectStatus()
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        .expectHeader()
        .contentType("application/json")
        .expectBody()
        .jsonPath("$.is_success")
        .isEqualTo(false)
        .jsonPath("$.code")
        .isEqualTo("COMMON_429")
        .jsonPath("$.message")
        .isEqualTo("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
  }

  @Test
  @DisplayName("서로 다른 IP는 서로 다른 Rate Limit bucket을 사용한다")
  void 서로_다른_IP는_bucket을_공유하지_않는다() {
    String firstIp = "198.51.100.20";
    String secondIp = "198.51.100.21";

    requestWithIp(firstIp).expectStatus().isOk();
    requestWithIp(secondIp).expectStatus().isOk();

    requestWithIp(firstIp).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    requestWithIp(secondIp).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  @DisplayName("인증 사용자는 같은 IP에서도 userId별 Rate Limit bucket을 사용한다")
  void 인증_사용자는_userId별로_bucket을_사용한다() {
    String sharedIp = "198.51.100.30";

    String user101Token = accessToken(101L);
    String user102Token = accessToken(102L);

    requestWithToken(sharedIp, user101Token).expectStatus().isOk();
    requestWithToken(sharedIp, user102Token).expectStatus().isOk();

    requestWithToken(sharedIp, user101Token).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    requestWithToken(sharedIp, user102Token).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  @DisplayName("외부 X-User-Id 위조로 IP Rate Limit을 우회할 수 없다")
  void 가짜_XUserId로_IP_RateLimit을_우회할_수_없다() {
    String clientIp = "198.51.100.40";

    webTestClient
        .get()
        .uri("/__rate-limit-probe")
        .header("X-Forwarded-For", clientIp)
        .header("X-User-Id", "1001")
        .exchange()
        .expectStatus()
        .isOk();

    // JwtAuthenticationFilter가 외부에서 보낸 X-User-Id를 제거하므로
    // 다른 값을 보내도 동일한 IP bucket을 사용해야 한다.
    webTestClient
        .get()
        .uri("/__rate-limit-probe")
        .header("X-Forwarded-For", clientIp)
        .header("X-User-Id", "9999")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  @DisplayName("Redis에 route와 key별 tokens, timestamp 키가 생성된다")
  void Redis에_RateLimit_key가_생성된다() {
    String clientIp = "198.51.100.50";

    requestWithIp(clientIp).expectStatus().isOk();

    List<String> keys =
        redisTemplate.keys("request_rate_limiter.*").collectList().block(Duration.ofSeconds(5));

    assertThat(keys)
        .isNotNull()
        .containsExactlyInAnyOrder(
            "request_rate_limiter.{" + ROUTE_ID + ".ip:" + clientIp + "}.tokens",
            "request_rate_limiter.{" + ROUTE_ID + ".ip:" + clientIp + "}.timestamp");
  }

  private WebTestClient.ResponseSpec requestWithIp(String clientIp) {
    return webTestClient
        .get()
        .uri("/__rate-limit-probe")
        .header("X-Forwarded-For", clientIp)
        .exchange();
  }

  private WebTestClient.ResponseSpec requestWithToken(String clientIp, String accessToken) {

    return webTestClient
        .get()
        .uri("/__rate-limit-probe")
        .header("X-Forwarded-For", clientIp)
        .header("Authorization", "Bearer " + accessToken)
        .exchange();
  }

  private String accessToken(long userId) {
    return jwtTokenProvider.createAccessToken(userId, "USER");
  }

  /**
   * Rate Limit을 통과한 요청이 도착하는 테스트 전용 내부 endpoint.
   *
   * <p>실제 booking/payment/auth 서비스가 없어도 Gateway가 RateLimiter를 통과해 정상 라우팅 단계까지 도달했는지를 확인한다.
   */
  @RestController
  static class RateLimitProbeController {

    @GetMapping("/__rate-limit-ok")
    String ok() {
      return "ok";
    }
  }
}
