package com.ticketrush.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.security.JwtTokenProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 대기열을 실제 Redis(Testcontainers)와 게이트웨이 컨텍스트로 검증한다(#472 / ADR 0009).
 *
 * <p>이 테스트의 핵심은 "상태 확인 경로는 JWT 필터를 타지 않는다" 케이스다. ADR §4의 그 요구가 실제로 성립하는지는 코드를 읽어서 확인할 수 없고 — {@code
 * GlobalFilter} 가 도는지는 라우트 매칭 결과에 달려 있다 — 요청을 넣어 봐야 알 수 있다.
 *
 * <p>{@code admit-rate-per-second: 1} 이라 진입 직후에는 아무도 통과하지 못하고 1초 뒤 선두 한 명이 승급한다. <b>테스트마다 다른
 * performanceId를 쓴다</b> — 같은 ZSET을 공유하면 순번이 실행 순서에 따라 달라져 승급 시점이 흔들린다.
 *
 * <p>Docker가 없는 환경에서는 컨테이너 기동 실패로 깨진다 — 의도된 fail-closed(payment #422 선례).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "jwt.secret=test-secret-key-for-waiting-room-integration-test",
      "queue.enabled=true",
      "queue.admit-rate-per-second=1"
    })
@Testcontainers
class WaitingRoomGatewayTest {

  /** prod(deploy/docker-compose.prod.yml)와 동일한 redis:7-alpine. */
  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  /** admit-rate-per-second=1 기준으로 선두(rank 0)의 허용선이 열리기까지 필요한 시간 + 여유. */
  private static final long ADMISSION_WAIT_MS = 1_200L;

  private static final long USER_ID = 42L;

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
  void bindClient() {
    // 실제 포트에 바인딩한다. 대기열 경로가 GlobalFilter를 타는지는 게이트웨이 전체 파이프라인을 지나야
    // 드러나므로, 컨트롤러만 띄우는 방식(bindToController)으로는 이 테스트의 목적을 검증할 수 없다.
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(10))
            .build();
  }

  @Test
  @DisplayName("상태 확인 경로는 JWT 필터를 타지 않는다 — 깨진 Bearer 토큰을 달아도 인증 오류가 아니다")
  void 상태확인은_인증필터를_타지_않는다() {
    // JwtAuthenticationFilter가 돌았다면 서명 검증에 실패해 AUTH_401_003(핸들러가 없으니 실제로는 500)이
    // 나온다. 대기열 코드가 응답했다는 것은 그 필터가 아예 실행되지 않았다는 뜻이다.
    webTestClient
        .get()
        .uri("/api/v1/queue/{performanceId}/status", 101L)
        .header("Authorization", "Bearer this-is-not-a-jwt")
        .header("X-Waiting-Token", "unknown-token")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("QUEUE_403_002");
  }

  @Test
  @DisplayName("진입하면 순번을 받고, 허용선 밖이면 다음 폴링 시각을 지시받는다")
  void 진입_후_대기() {
    long performanceId = 102L;
    String waitingToken = enqueue(performanceId, USER_ID);

    byte[] body =
        webTestClient
            .get()
            .uri("/api/v1/queue/{performanceId}/status", performanceId)
            .header("X-Waiting-Token", waitingToken)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            // 응답은 전역 snake_case(JacksonConfig).
            .jsonPath("$.result.next_poll_after_seconds")
            .isNumber()
            .jsonPath("$.result.entry_token")
            .doesNotExist()
            .returnResult()
            .getResponseBody();

    // ADR 0009 §4 "응답을 작게 유지한다" — 참고 상한은 seat-counts 응답 203 bytes. 1만 명이 25초마다
    // 받아 가는 값이라 회선과 직렬화 CPU에 그대로 곱해진다. 필드를 늘리면 여기서 걸린다.
    assertThat(body).isNotNull();
    assertThat(body.length).isLessThanOrEqualTo(203);
  }

  @Test
  @DisplayName("허용선 안으로 들어오면 입장 토큰을 발급받고, 그 토큰이 예매 게이트를 통과한다")
  void 승급_후_예매_게이트_통과() throws InterruptedException {
    long performanceId = 103L;
    String waitingToken = enqueue(performanceId, USER_ID);

    Thread.sleep(ADMISSION_WAIT_MS);

    // 입장 토큰은 대기 토큰과 같은 문자열이다 — 재폴링이 멱등해야 승급 응답을 놓친 사용자가 입장할 수 있다.
    webTestClient
        .get()
        .uri("/api/v1/queue/{performanceId}/status", performanceId)
        .header("X-Waiting-Token", waitingToken)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.result.entry_token")
        .isEqualTo(waitingToken);

    // 게이트를 통과하면 다운스트림(booking-service)으로 라우팅되고, 테스트 환경엔 그게 없어 연결에 실패한다.
    // 여기서 확인하는 것은 "403이 아니다" — 게이트에서 막히지 않았다는 것뿐이다.
    webTestClient
        .post()
        .uri("/api/v1/booking")
        .header("Authorization", "Bearer " + accessToken(USER_ID))
        .header("X-Entry-Token", waitingToken)
        .bodyValue("{\"performance_id\":103,\"seat_id\":1}")
        .exchange()
        .expectStatus()
        .value(status -> assertThat(status).isNotEqualTo(HttpStatus.FORBIDDEN.value()));
  }

  @Test
  @DisplayName("입장 토큰 없는 예매는 다운스트림에 닿기 전에 게이트웨이가 거절한다")
  void 입장_토큰_없는_예매는_거절된다() {
    webTestClient
        .post()
        .uri("/api/v1/booking")
        .header("Authorization", "Bearer " + accessToken(USER_ID))
        .bodyValue("{\"performance_id\":104,\"seat_id\":1}")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("QUEUE_403_001");
  }

  @Test
  @DisplayName("폴링 1회가 쓰는 Redis 명령은 GET + ZRANK 두 번뿐이다")
  void 폴링은_Redis_명령_두_번만_쓴다() {
    long performanceId = 106L;
    String waitingToken = enqueue(performanceId, USER_ID);

    // 첫 폴링이 개시 시각·대기 인원을 로컬 캐시에 채운다. 이후가 정상 상태다.
    poll(performanceId, waitingToken);

    resetCommandStats();
    poll(performanceId, waitingToken);

    // ADR 0009 §4 "상태 확인 경로 초경량화"의 유일한 객관 증거다. 여기에 명령이 하나 늘면
    // 1만 명 × 폴링 주기만큼 Redis 부하가 그대로 곱해지고, 폴링 주기 다이얼의 상한이 내려간다.
    assertThat(commandCount("get")).isEqualTo(1L);
    assertThat(commandCount("zrank")).isEqualTo(1L);
    assertThat(commandCount("zcard")).isZero(); // 대기 인원은 10초 로컬 캐시에서 답한다
    assertThat(commandCount("set")).isZero(); // 승급 전에는 쓰기가 없다
  }

  @Test
  @DisplayName("남의 입장 토큰으로는 통과하지 못한다")
  void 타인의_입장_토큰은_거절된다() throws InterruptedException {
    long performanceId = 105L;
    long ownerId = USER_ID + 1;
    long attackerId = USER_ID + 2;

    String waitingToken = enqueue(performanceId, ownerId);
    Thread.sleep(ADMISSION_WAIT_MS);

    webTestClient
        .get()
        .uri("/api/v1/queue/{performanceId}/status", performanceId)
        .header("X-Waiting-Token", waitingToken)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.result.entry_token")
        .isEqualTo(waitingToken);

    webTestClient
        .post()
        .uri("/api/v1/booking")
        .header("Authorization", "Bearer " + accessToken(attackerId))
        .header("X-Entry-Token", waitingToken)
        .bodyValue("{\"performance_id\":105,\"seat_id\":1}")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("QUEUE_403_001");
  }

  private String accessToken(long userId) {
    return jwtTokenProvider.createAccessToken(userId, "USER");
  }

  private void poll(long performanceId, String waitingToken) {
    webTestClient
        .get()
        .uri("/api/v1/queue/{performanceId}/status", performanceId)
        .header("X-Waiting-Token", waitingToken)
        .exchange()
        .expectStatus()
        .isOk();
  }

  private void resetCommandStats() {
    redisTemplate
        .execute(connection -> connection.serverCommands().resetConfigStats())
        .blockLast(Duration.ofSeconds(5));
  }

  /** {@code INFO commandstats} 의 {@code cmdstat_<name>:calls=N,...} 에서 호출 수를 꺼낸다. */
  private long commandCount(String command) {
    Properties stats =
        redisTemplate
            .execute(connection -> connection.serverCommands().info("commandstats"))
            .blockLast(Duration.ofSeconds(5));

    assertThat(stats).isNotNull();
    String entry = stats.getProperty("cmdstat_" + command);
    if (entry == null) {
      return 0L;
    }
    for (String part : entry.split(",")) {
      if (part.startsWith("calls=")) {
        return Long.parseLong(part.substring("calls=".length()));
      }
    }
    return 0L;
  }

  private String enqueue(long performanceId, long userId) {
    byte[] body =
        webTestClient
            .post()
            .uri("/api/v1/queue/{performanceId}/enqueue", performanceId)
            .header("Authorization", "Bearer " + accessToken(userId))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody()
            .returnResult()
            .getResponseBody();

    assertThat(body).isNotNull();
    return extract(new String(body, StandardCharsets.UTF_8), "waiting_token");
  }

  private static String extract(String json, String field) {
    String marker = "\"" + field + "\":\"";
    int start = json.indexOf(marker);
    assertThat(start).isNotNegative();
    start += marker.length();
    return json.substring(start, json.indexOf('"', start));
  }
}
