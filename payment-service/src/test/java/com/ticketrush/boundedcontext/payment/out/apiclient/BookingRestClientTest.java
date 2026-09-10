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
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

  private MockRestServiceServer mockServer;
  private BookingRestClient client;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();

    CustomSecurityProperties customSecurityProperties = new CustomSecurityProperties();
    customSecurityProperties.setInternalToken(INTERNAL_TOKEN);

    meterRegistry = new SimpleMeterRegistry();
    client = new BookingRestClient(builder.build(), customSecurityProperties, meterRegistry);
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
  @DisplayName("계측: BusinessException 이 아닌 예외가 새어도 success 로 집계되지 않는다 (outcome 초기값 계약)")
  void getBooking_records_failed_outcome_when_non_business_exception_escapes() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(
            request -> {
              throw new IllegalStateException("boom");
            });

    assertThatThrownBy(() -> client.getBooking(BOOKING_ID))
        .isNotInstanceOf(BusinessException.class);

    // getBooking 이 outcome 초기값을 failed 로 두는 이유가 이 경로다. success 로 두면 판정 불가로
    // 끝난 호출이 정상 왕복 분포에 섞여 #571 임계값의 근거가 오염된다.
    assertThat(lookupTimer("failed")).isNotNull();
    assertThat(lookupTimer("failed").count()).isEqualTo(1);
    assertThat(lookupTimer("success")).isNull();
  }
}
