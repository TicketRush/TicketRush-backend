package com.ticketrush.boundedcontext.ticket.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ticketrush.boundedcontext.ticket.out.apiclient.dto.BookingInfoResponse;
import com.ticketrush.global.config.BookingCircuitBreakerConfig;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BookingRestClientTest {

  private static final String BASE_URL = "http://localhost:8084";
  private static final String INTERNAL_TOKEN = "test-token";
  private static final long BOOKING_ID = 100L;
  private static final String REQUEST_URL = BASE_URL + "/api/v1/internal/booking/" + BOOKING_ID;

  /** 테스트가 몇 번 만에 서킷을 열 수 있는지. 프로덕션(20/10)을 그대로 쓰면 테스트가 불필요하게 길어진다. */
  private static final int TEST_WINDOW = 4;

  private static final Duration TEST_WAIT_IN_OPEN = Duration.ofMillis(100);

  private static final String SUCCESS_BODY =
      String.join(
          "\n",
          "{",
          "  \"is_success\": true,",
          "  \"code\": \"COMMON_200\",",
          "  \"message\": \"성공입니다.\",",
          "  \"result\": {",
          "    \"booking_id\": 100,",
          "    \"user_id\": 10,",
          "    \"booking_status\": \"CONFIRMED\"",
          "  }",
          "}");

  private MockRestServiceServer mockServer;
  private CircuitBreakerRegistry circuitBreakerRegistry;
  private BookingRestClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();

    CustomSecurityProperties customSecurityProperties = new CustomSecurityProperties();
    customSecurityProperties.setInternalToken(INTERNAL_TOKEN);

    // 실패 판정 규칙(recordException)은 프로덕션 설정에서 그대로 승계하고 윈도우·대기 시간만 줄인다.
    CircuitBreakerConfig testConfig =
        CircuitBreakerConfig.from(BookingCircuitBreakerConfig.bookingConfig())
            .slidingWindowSize(TEST_WINDOW)
            .minimumNumberOfCalls(TEST_WINDOW)
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

    client = new BookingRestClient(builder.build(), customSecurityProperties, factory);
  }

  private CircuitBreaker.State circuitState() {
    return circuitBreakerRegistry
        .circuitBreaker(BookingCircuitBreakerConfig.BOOKING_CIRCUIT_BREAKER)
        .getState();
  }

  private void expectOnce(org.springframework.test.web.client.response.DefaultResponseCreator r) {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(r);
  }

  @Test
  @DisplayName("성공: 공통 ApiResponse 래퍼의 snake_case 본문을 역직렬화하고 X-Internal-Token을 전송한다")
  void getBooking_deserializes_snake_case_and_sends_token() {
    expectOnce(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

    BookingInfoResponse result = client.getBooking(BOOKING_ID);

    assertThat(result.bookingId()).isEqualTo(100L);
    assertThat(result.userId()).isEqualTo(10L);
    assertThat(result.bookingStatus()).isEqualTo("CONFIRMED");

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 예매 없음(404)은 TICKET_NOT_FOUND로 통일한다")
  void getBooking_maps_404_to_not_found() {
    expectOnce(withStatus(HttpStatus.NOT_FOUND));

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_FOUND);

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 토큰 불일치(403)는 사용자에게 500을 노출하지 않고 통신 실패로 매핑한다")
  void getBooking_maps_403_to_communication_failed() {
    expectOnce(withStatus(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.TICKET_BOOKING_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: booking-service 5xx는 통신 실패로 매핑한다")
  void getBooking_maps_5xx_to_communication_failed() {
    expectOnce(withServerError());

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.TICKET_BOOKING_COMMUNICATION_FAILED);

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

    expectOnce(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.TICKET_BOOKING_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("서킷: 다운스트림 실패가 임계를 넘으면 CLOSED -> OPEN 으로 전이하고, 이후 호출은 booking 을 치지 않는다")
  void circuit_opens_after_repeated_downstream_failures() {
    mockServer
        .expect(manyTimes(), requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withServerError());

    for (int i = 0; i < TEST_WINDOW; i++) {
      assertThatThrownBy(() -> client.getBooking(BOOKING_ID)).isInstanceOf(BusinessException.class);
    }

    assertThat(circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

    // OPEN 상태의 호출은 booking 왕복 없이 즉시 503 으로 떨어진다(fail-fast). 검표는 권위 있는
    // bookingStatus 없이 통과시킬 수 없으므로 폴백이 '통과'가 아니라 '차단'인 것이 이 테스트의 핵심이다.
    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.TICKET_BOOKING_COMMUNICATION_FAILED);
  }

  @Test
  @DisplayName("서킷: OPEN 이후 대기 시간이 지나면 HALF_OPEN 으로 열리고, 성공이 이어지면 CLOSED 로 복귀한다")
  void circuit_recovers_through_half_open_to_closed() throws InterruptedException {
    mockServer
        .expect(manyTimes(), requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withServerError());

    for (int i = 0; i < TEST_WINDOW; i++) {
      assertThatThrownBy(() -> client.getBooking(BOOKING_ID)).isInstanceOf(BusinessException.class);
    }
    assertThat(circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

    Thread.sleep(TEST_WAIT_IN_OPEN.toMillis() + 50);
    circuitBreakerRegistry
        .circuitBreaker(BookingCircuitBreakerConfig.BOOKING_CIRCUIT_BREAKER)
        .transitionToHalfOpenState();
    assertThat(circuitState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

    // booking 이 살아난 상황. 반열림 허용 호출(2건)이 모두 성공하면 CLOSED 로 돌아간다.
    mockServer.reset();
    mockServer
        .expect(manyTimes(), requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

    assertThat(client.getBooking(BOOKING_ID).bookingStatus()).isEqualTo("CONFIRMED");
    assertThat(client.getBooking(BOOKING_ID).bookingStatus()).isEqualTo("CONFIRMED");

    assertThat(circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("서킷: 호출은 별도 스레드풀이 아니라 호출 스레드에서 실행된다")
  void circuit_runs_on_caller_thread() {
    // disable-threadpool 을 켠 이유가 여기 있다. 풀에 넘기면 톰캣 스레드는 Future 를 기다리고
    // 풀 스레드가 실제 호출을 잡아 요청당 스레드를 둘 다 점유한다 — 스레드 고갈을 줄이려는
    // 이 이슈의 목적과 정반대다. 상한은 RestClient 의 read-timeout 이 준다.
    Thread callerThread = Thread.currentThread();
    AtomicReference<Thread> executingThread = new AtomicReference<>();
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            request -> {
              executingThread.set(Thread.currentThread());
              return withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON).createResponse(request);
            });

    client.getBooking(BOOKING_ID);

    assertThat(executingThread.get()).isSameAs(callerThread);
  }

  @Test
  @DisplayName("서킷: 예매 없음(404)은 booking 이 정상일 때 나오는 응답이므로 서킷을 열지 않는다")
  void circuit_stays_closed_on_not_found() {
    mockServer
        .expect(manyTimes(), requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    // 존재하지 않는 예매를 반복 스캔하는 것만으로 서킷이 열리면 멀쩡한 검표가 전부 503 이 된다.
    for (int i = 0; i < TEST_WINDOW * 2; i++) {
      assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.TICKET_NOT_FOUND);
    }

    assertThat(circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }
}
