package com.ticketrush.boundedcontext.booking.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TicketRestClientTest {

  private static final String BASE_URL = "http://localhost:8087";
  private static final String INTERNAL_TOKEN = "test-token";
  private static final long BOOKING_ID = 100L;
  private static final String REQUEST_URL =
      BASE_URL + "/api/v1/internal/ticket/bookings/" + BOOKING_ID;

  private MockRestServiceServer mockServer;
  private TicketRestClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();

    CustomSecurityProperties customSecurityProperties = new CustomSecurityProperties();
    customSecurityProperties.setInternalToken(INTERNAL_TOKEN);

    client = new TicketRestClient(builder.build(), customSecurityProperties);
  }

  private static String successBody(String ticketStatus) {
    return String.join(
        "\n",
        "{",
        "  \"is_success\": true,",
        "  \"code\": \"COMMON_200\",",
        "  \"message\": \"성공입니다.\",",
        "  \"result\": {",
        "    \"booking_id\": 100,",
        "    \"ticket_status\": \"" + ticketStatus + "\"",
        "  }",
        "}");
  }

  private void expectGetAndRespondWith(String ticketStatus) {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(withSuccess(successBody(ticketStatus), MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName("성공: USED 입장권은 사용됨(true)으로 판정하고 X-Internal-Token을 전송한다")
  void isTicketUsed_returns_true_for_used_ticket() {
    expectGetAndRespondWith("USED");

    assertThat(client.isTicketUsed(BOOKING_ID)).isTrue();

    mockServer.verify();
  }

  @Test
  @DisplayName("성공: UNUSED 입장권은 미사용(false)으로 판정한다")
  void isTicketUsed_returns_false_for_unused_ticket() {
    expectGetAndRespondWith("UNUSED");

    assertThat(client.isTicketUsed(BOOKING_ID)).isFalse();

    mockServer.verify();
  }

  @Test
  @DisplayName("성공: CANCELED 입장권은 미사용(false)으로 판정한다 — 입장하지 않은 채 취소된 예매다")
  void isTicketUsed_returns_false_for_canceled_ticket() {
    expectGetAndRespondWith("CANCELED");

    assertThat(client.isTicketUsed(BOOKING_ID)).isFalse();

    mockServer.verify();
  }

  @Test
  @DisplayName("성공: 입장권 없음(404)은 발급 전이므로 미사용(false)으로 판정한다 — 예외를 던지지 않는다")
  void isTicketUsed_returns_false_when_ticket_not_found() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(client.isTicketUsed(BOOKING_ID)).isFalse();

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 토큰 불일치(403)는 통신 실패로 매핑해 취소를 거부한다(알 수 없으면 막는다)")
  void isTicketUsed_maps_403_to_communication_failed() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> client.isTicketUsed(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_TICKET_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: ticket-service 5xx는 통신 실패로 매핑한다")
  void isTicketUsed_maps_5xx_to_communication_failed() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(withServerError());

    assertThatThrownBy(() -> client.isTicketUsed(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_TICKET_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 200이지만 본문이 JSON이 아니면 원시 500이 아니라 통신 실패로 매핑한다")
  void isTicketUsed_maps_unparsable_body_to_communication_failed() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Internal-Token", INTERNAL_TOKEN))
        .andRespond(withSuccess("<html>gateway error</html>", MediaType.TEXT_HTML));

    assertThatThrownBy(() -> client.isTicketUsed(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_TICKET_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("실패: 200이지만 result 본문이 비어 있으면 통신 실패로 매핑한다")
  void isTicketUsed_maps_empty_result_to_communication_failed() {
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

    assertThatThrownBy(() -> client.isTicketUsed(BOOKING_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue(
            "errorStatus", ErrorStatus.BOOKING_TICKET_COMMUNICATION_FAILED);

    mockServer.verify();
  }
}
