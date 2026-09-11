package com.ticketrush.boundedcontext.payment.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ticketrush.boundedcontext.payment.out.apiclient.dto.BookingInfoResponse;
import com.ticketrush.global.config.BookingCircuitBreakerConfig;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

class BookingRestClientTest {

  private static final String BASE_URL = "http://localhost:8084";
  private static final String INTERNAL_TOKEN = "test-token";
  private static final long BOOKING_ID = 100L;
  private static final String REQUEST_URL = BASE_URL + "/api/v1/internal/booking/" + BOOKING_ID;

  /** 테스트가 몇 번 만에 서킷을 열 수 있는지. 프로덕션(10)을 그대로 쓰면 테스트가 불필요하게 길어진다. */
  private static final int TEST_MIN_CALLS = 4;

  private static final Duration TEST_WAIT_IN_OPEN = Duration.ofMillis(100);

  private MockRestServiceServer mockServer;
  private BookingRestClient client;
  private SimpleMeterRegistry meterRegistry;
  private RestClient.Builder builder;
  private CircuitBreakerRegistry circuitBreakerRegistry;
  private Resilience4JCircuitBreakerFactory circuitBreakerFactory;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();

    CustomSecurityProperties customSecurityProperties = new CustomSecurityProperties();
    customSecurityProperties.setInternalToken(INTERNAL_TOKEN);

    meterRegistry = new SimpleMeterRegistry();
    circuitBreakerFactory = testCircuitBreakerFactory();
    client =
        new BookingRestClient(
            builder.build(), customSecurityProperties, meterRegistry, circuitBreakerFactory, true);
  }

  /**
   * 실패 판정 규칙({@code recordException})은 프로덕션 설정에서 그대로 승계하고 게이팅·대기 시간만 줄인다. 규칙을 여기서 손으로 복제하면 프로덕션 규칙이
   * 바뀌어도 테스트가 눈치채지 못한다.
   *
   * <p>느린호출 임계만은 예외로 크게 벌린다. 프로덕션 값은 100ms 인데 {@code MockRestServiceServer} 왕복이라도 첫 호출은 JIT·
   * Jackson 초기화로 그 선을 넘을 수 있고, 그러면 <b>테스트가 의도하지 않은 이유로 서킷을 열어</b> 간헐 실패가 된다. 이 테스트가 고정하려는 것은 느린호출
   * 축이 아니라 실패 판정·차단·복귀 동작이다.
   */
  private Resilience4JCircuitBreakerFactory testCircuitBreakerFactory() {
    CircuitBreakerConfig testConfig =
        CircuitBreakerConfig.from(BookingCircuitBreakerConfig.bookingConfig())
            .minimumNumberOfCalls(TEST_MIN_CALLS)
            .slowCallDurationThreshold(Duration.ofSeconds(10))
            .waitDurationInOpenState(TEST_WAIT_IN_OPEN)
            .permittedNumberOfCallsInHalfOpenState(2)
            .build();

    circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    Resilience4JConfigurationProperties properties = new Resilience4JConfigurationProperties();
    properties.setDisableThreadPool(true);
    Resilience4JCircuitBreakerFactory factory =
        new Resilience4JCircuitBreakerFactory(
            circuitBreakerRegistry, TimeLimiterRegistry.ofDefaults(), null, properties);
    factory.configure(
        b -> b.circuitBreakerConfig(testConfig),
        BookingCircuitBreakerConfig.BOOKING_CIRCUIT_BREAKER);
    return factory;
  }

  private CircuitBreaker.State circuitState() {
    return circuitBreakerRegistry
        .circuitBreaker(BookingCircuitBreakerConfig.BOOKING_CIRCUIT_BREAKER)
        .getState();
  }

  /** outcome 태그가 붙은 왕복 Timer 를 찾는다. 없으면 null(= 그 갈래로 기록되지 않았다). */
  private Timer lookupTimer(String outcome) {
    return meterRegistry
        .find(MetricNames.PAYMENT_BOOKING_LOOKUP)
        .tag(MetricNames.TAG_OUTCOME, outcome)
        .timer();
  }

  private static String successBody(String bookingStatus) {
    return String.join(
        "\n",
        "{",
        "  \"is_success\": true,",
        "  \"code\": \"COMMON_200\",",
        "  \"message\": \"성공입니다.\",",
        "  \"result\": {",
        "    \"booking_id\": 100,",
        "    \"user_id\": 10,",
        "    \"booking_status\": \"" + bookingStatus + "\"",
        "  }",
        "}");
  }

  /** booking-service가 예매 없음 시 내려주는 에러 본문(공통 ApiResponse 형식). */
  private static String bookingNotFoundBody() {
    return String.join(
        "\n",
        "{",
        "  \"is_success\": false,",
        "  \"code\": \"BOOKING_404_001\",",
        "  \"message\": \"해당 예매를 찾을 수 없습니다.\"",
        "}");
  }

  private void expectGetAndRespondWith(String bookingStatus) {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(withSuccess(successBody(bookingStatus), MediaType.APPLICATION_JSON));
  }

  /** 서킷 테스트는 같은 응답을 여러 번 세워야 해서 응답 생성기를 인자로 받는 형태가 필요하다. */
  private void expectOnce(ResponseCreator responseCreator) {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(responseCreator);
  }

  private void expectGetAndRespondWithStatus(HttpStatus status) {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(withStatus(status));
  }

  @Test
  @DisplayName("성공: PENDING 예매를 조회하고 X-Internal-Token을 전송한다")
  void getBooking_returns_pending_booking() {
    expectGetAndRespondWith("PENDING");

    BookingInfoResponse result = client.getBooking(BOOKING_ID);

    assertThat(result.bookingId()).isEqualTo(BOOKING_ID);
    assertThat(result.userId()).isEqualTo(10L);
    assertThat(result.bookingStatus()).isEqualTo("PENDING");

    mockServer.verify();
  }

  @Test
  @DisplayName("성공: EXPIRED 상태도 그대로 반환한다 — 결제 허용 여부 판정은 UseCase의 책임이다")
  void getBooking_returns_expired_status_as_is() {
    expectGetAndRespondWith("EXPIRED");

    assertThat(client.getBooking(BOOKING_ID).bookingStatus()).isEqualTo("EXPIRED");

    mockServer.verify();
  }

  @Test
  @DisplayName("성공: CANCELED 상태도 그대로 반환한다")
  void getBooking_returns_canceled_status_as_is() {
    expectGetAndRespondWith("CANCELED");

    assertThat(client.getBooking(BOOKING_ID).bookingStatus()).isEqualTo("CANCELED");

    mockServer.verify();
  }

  @Test
  @DisplayName("성공: 알 수 없는 상태 문자열도 클라이언트는 거르지 않는다 — 차단은 UseCase의 허용 목록이 한다")
  void getBooking_returns_unknown_status_as_is() {
    expectGetAndRespondWith("SOMETHING_NEW");

    assertThat(client.getBooking(BOOKING_ID).bookingStatus()).isEqualTo("SOMETHING_NEW");

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: BOOKING_404_001(예매 없음)은 BOOKING_NOT_FOUND로 전파한다")
  void getBooking_maps_booking_not_found_code_to_booking_not_found() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(bookingNotFoundBody()));

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.BOOKING_NOT_FOUND);

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 본문 없는 404(경로·라우팅 오설정)는 예매 없음으로 해석하지 않는다 — 설정 오류가 묻히면 안 된다")
  void getBooking_maps_404_without_body_to_communication_failed() {
    expectGetAndRespondWithStatus(HttpStatus.NOT_FOUND);

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 다른 code의 404도 예매 없음으로 해석하지 않는다")
  void getBooking_maps_404_with_other_code_to_communication_failed() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"is_success\": false, \"code\": \"COMMON_404\"}"));

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 토큰 불일치(403)는 통신 실패로 매핑해 결제를 거부한다(조회 불가면 막는다)")
  void getBooking_maps_403_to_communication_failed() {
    expectGetAndRespondWithStatus(HttpStatus.FORBIDDEN);

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: booking-service 5xx는 통신 실패로 매핑한다")
  void getBooking_maps_5xx_to_communication_failed() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(withServerError());

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 200이지만 본문이 JSON이 아니면 원시 500이 아니라 통신 실패로 매핑한다")
  void getBooking_maps_unparsable_body_to_communication_failed() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(withSuccess("<html>gateway error</html>", MediaType.TEXT_HTML));

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("성공: booking_status 필드가 없으면 null로 매핑한다 — 판정은 UseCase가 하고 500으로 새지 않아야 한다")
  void getBooking_maps_missing_status_field_to_null() {
    String responseBody =
        String.join(
            "\n",
            "{",
            "  \"is_success\": true,",
            "  \"code\": \"COMMON_200\",",
            "  \"result\": {",
            "    \"booking_id\": 100,",
            "    \"user_id\": 10",
            "  }",
            "}");

    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    assertThat(client.getBooking(BOOKING_ID).bookingStatus()).isNull();

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 연결 실패·타임아웃도 통신 실패로 매핑한다 — fail-closed가 가장 자주 타는 경로다")
  void getBooking_maps_connection_failure_to_communication_failed() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(
            request -> {
              throw new IOException("connection refused");
            });

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 200이지만 result 본문이 비어 있으면 통신 실패로 매핑한다")
  void getBooking_maps_empty_result_to_communication_failed() {
    String responseBody =
        String.join(
            "\n",
            "{",
            "  \"is_success\": true,",
            "  \"code\": \"COMMON_200\",",
            "  \"message\": \"성공입니다.\",",
            "  \"result\": null",
            "}");

    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("계측: 정상 조회는 왕복 Timer 를 outcome=success 로 한 건 기록한다 (#633)")
  void getBooking_records_lookup_timer_with_success_outcome() {
    expectGetAndRespondWith("PENDING");

    client.getBooking(BOOKING_ID);

    assertThat(lookupTimer("success")).isNotNull();
    assertThat(lookupTimer("success").count()).isEqualTo(1);
    assertThat(lookupTimer("not_found")).isNull();
    assertThat(lookupTimer("failed")).isNull();

    mockServer.verify();
  }

  @Test
  @DisplayName("계측: 예매 없음(404)은 outcome=not_found 로 갈라 기록한다 — #571이 404를 서킷 실패로 세지 않기 때문이다")
  void getBooking_records_lookup_timer_with_not_found_outcome() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(bookingNotFoundBody()));

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID)).isInstanceOf(BusinessException.class);

    assertThat(lookupTimer("not_found")).isNotNull();
    assertThat(lookupTimer("not_found").count()).isEqualTo(1);
    assertThat(lookupTimer("success")).isNull();
    assertThat(lookupTimer("failed")).isNull();

    mockServer.verify();
  }

  @Test
  @DisplayName("계측: 통신 실패도 왕복 Timer 에 outcome=failed 로 남는다 — 실패 건이 빠지면 느린 실패가 분포에서 사라진다")
  void getBooking_records_lookup_timer_with_failed_outcome() {
    expectGetAndRespondWithStatus(HttpStatus.FORBIDDEN);

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID)).isInstanceOf(BusinessException.class);

    assertThat(lookupTimer("failed")).isNotNull();
    assertThat(lookupTimer("failed").count()).isEqualTo(1);
    assertThat(lookupTimer("success")).isNull();
    assertThat(lookupTimer("not_found")).isNull();

    mockServer.verify();
  }

  @Test
  @DisplayName("계측: BusinessException 이 아닌 예외가 나도 success 로 집계되지 않는다 (outcome 초기값 계약)")
  void getBooking_records_failed_outcome_when_non_business_exception_escapes() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(
            request -> {
              throw new IllegalStateException("boom");
            });

    // #571 이전에는 이 예외가 그대로 새어 confirm 경로에서 원시 500 이 됐다(그쪽에는 비
    // BusinessException catch 가 없다). 이제 fallback 이 받아 503 으로 수렴시킨다.
    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);

    // outcome 초기값을 failed 로 두는 이유가 이 경로다. success 로 두면 판정 불가로 끝난 호출이
    // 정상 왕복 분포에 섞여 #571 임계값의 근거가 오염된다. Timer 는 fallback 보다 안쪽이라
    // 여기까지는 서킷 도입과 무관하게 그대로다.
    assertThat(lookupTimer("failed")).isNotNull();
    assertThat(lookupTimer("failed").count()).isEqualTo(1);
    assertThat(lookupTimer("success")).isNull();
  }

  @Test
  @DisplayName("서킷: 다운스트림 5xx 가 반복되면 서킷이 열리고, 이후 호출은 booking 을 치지 않는다")
  void circuit_opens_after_repeated_downstream_failures() {
    for (int i = 0; i < TEST_MIN_CALLS; i++) {
      expectOnce(withServerError());
    }

    for (int i = 0; i < TEST_MIN_CALLS; i++) {
      assertThatThrownBy(() -> client.getBooking(BOOKING_ID)).isInstanceOf(BusinessException.class);
    }

    assertThat(circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

    // 기대한 요청은 위 4건뿐이다. 열린 뒤의 호출이 booking 을 한 번이라도 더 쳤다면 verify 가 깨진다 —
    // 폴백이 '통과'가 아니라 '차단'인 것이 이 테스트의 핵심이다.
    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("서킷: 차단된 호출은 왕복 Timer 에 남지 않는다 — 서킷이 Timer 보다 바깥이다")
  void blocked_calls_are_not_recorded_in_lookup_timer() {
    for (int i = 0; i < TEST_MIN_CALLS; i++) {
      expectOnce(withServerError());
    }
    for (int i = 0; i < TEST_MIN_CALLS; i++) {
      assertThatThrownBy(() -> client.getBooking(BOOKING_ID)).isInstanceOf(BusinessException.class);
    }
    assertThat(circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

    long recordedBeforeBlock = lookupTimer("failed").count();

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID)).isInstanceOf(BusinessException.class);

    // 0ms 짜리 차단 표본이 분포에 섞이면 #633 이 만든 왕복 지연 분포가 오염되어 임계값을 다시 도출할
    // 축을 잃는다. 차단 건수는 resilience4j 의 not_permitted_calls_total 로 따로 본다.
    assertThat(lookupTimer("failed").count()).isEqualTo(recordedBeforeBlock);
  }

  @Test
  @DisplayName("서킷: 예매 없음(404)은 반복해도 서킷을 열지 않는다 — booking 이 정상일 때 나오는 응답이다")
  void circuit_stays_closed_on_booking_not_found() {
    int calls = TEST_MIN_CALLS * 2;
    for (int i = 0; i < calls; i++) {
      expectOnce(
          withStatus(HttpStatus.NOT_FOUND)
              .contentType(MediaType.APPLICATION_JSON)
              .body(bookingNotFoundBody()));
    }

    for (int i = 0; i < calls; i++) {
      assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.BOOKING_NOT_FOUND);
    }

    // 세면 존재하지 않는 예매로 결제를 반복 시도하는 것만으로 서킷이 열려 멀쩡한 결제가 전부 503 이 된다.
    assertThat(circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(lookupTimer("not_found").count()).isEqualTo(calls);
    mockServer.verify();
  }

  /**
   * ⚠ 이 테스트는 <b>{@code waitDurationInOpenState} 를 고정하지 않는다.</b> {@code transitionToHalfOpenState()}
   * 는 경과 시간을 보지 않고 무조건 전이하므로, 대기값이 10초든 10시간이든 여기는 통과한다. 자동 전이를 재려면 실제 대기가 필요해 테스트가 느려지고 타이밍에 흔들린다
   * — 그 대신 <b>half-open 에서 연속 성공하면 닫힌다</b>는 계약만 결정적으로 고정한다.
   */
  @Test
  @DisplayName("서킷: half-open 에서 연속 성공하면 다시 닫힌다")
  void circuit_recovers_through_half_open_to_closed() {
    for (int i = 0; i < TEST_MIN_CALLS; i++) {
      expectOnce(withServerError());
    }
    for (int i = 0; i < TEST_MIN_CALLS; i++) {
      assertThatThrownBy(() -> client.getBooking(BOOKING_ID)).isInstanceOf(BusinessException.class);
    }
    assertThat(circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

    mockServer.reset();
    for (int i = 0; i < 2; i++) {
      expectOnce(withSuccess(successBody("PENDING"), MediaType.APPLICATION_JSON));
    }

    circuitBreakerRegistry
        .circuitBreaker(BookingCircuitBreakerConfig.BOOKING_CIRCUIT_BREAKER)
        .transitionToHalfOpenState();

    for (int i = 0; i < 2; i++) {
      assertThat(client.getBooking(BOOKING_ID).bookingStatus()).isEqualTo("PENDING");
    }

    assertThat(circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("킬 스위치: enabled=false 면 서킷을 거치지 않고 매번 booking 을 친다")
  void kill_switch_bypasses_circuit_breaker() {
    CustomSecurityProperties customSecurityProperties = new CustomSecurityProperties();
    customSecurityProperties.setInternalToken(INTERNAL_TOKEN);
    BookingRestClient bypassed =
        new BookingRestClient(
            builder.build(), customSecurityProperties, meterRegistry, circuitBreakerFactory, false);

    int calls = TEST_MIN_CALLS * 2;
    for (int i = 0; i < calls; i++) {
      expectOnce(withServerError());
    }

    for (int i = 0; i < calls; i++) {
      assertThatThrownBy(() -> bypassed.getBooking(BOOKING_ID))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue(
              "errorStatus", ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED);
    }

    // 끈 결과는 #490 상태로 정확히 되돌아가는 것이다 — 서킷은 열리지 않고 호출도 차단되지 않는다.
    assertThat(circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    mockServer.verify();
  }
}
