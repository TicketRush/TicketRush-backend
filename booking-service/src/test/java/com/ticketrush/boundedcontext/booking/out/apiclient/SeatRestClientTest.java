package com.ticketrush.boundedcontext.booking.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ticketrush.global.config.CustomSecurityProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SeatRestClientTest {

  private static final String BASE_URL = "http://localhost:8086";
  private static final String REQUEST_URL = BASE_URL + "/api/v1/seat/numbers?seatIds=100,101";

  private MockRestServiceServer mockServer;
  private SeatRestClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();

    CustomSecurityProperties customSecurityProperties = new CustomSecurityProperties();
    customSecurityProperties.setInternalToken("test-token");

    client = new SeatRestClient(builder.build(), customSecurityProperties);
  }

  private static String successBody() {
    return String.join(
        "\n",
        "{",
        "  \"is_success\": true,",
        "  \"code\": \"COMMON_200\",",
        "  \"message\": \"성공입니다.\",",
        "  \"result\": [",
        "    {\"seat_id\": 100, \"seat_number\": \"A-1\"},",
        "    {\"seat_id\": 101, \"seat_number\": \"A-2\"}",
        "  ]",
        "}");
  }

  @Test
  @DisplayName("성공: 좌석 번호를 맵으로 변환하고, 공개 API라 X-Internal-Token을 보내지 않는다")
  void getSeatNumbers_returns_map_without_internal_token() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(headerDoesNotExist("X-Internal-Token"))
        .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));

    Map<Long, String> result = client.getSeatNumbers(List.of(100L, 101L));

    assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(100L, "A-1", 101L, "A-2"));
    mockServer.verify();
  }

  @Test
  @DisplayName("성공: 5xx는 빈 맵으로 수렴한다 — 예외를 밖으로 내지 않는다(부분 응답 정책)")
  void getSeatNumbers_returns_empty_map_on_5xx() {
    mockServer
        .expect(requestTo(REQUEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withServerError());

    assertThat(client.getSeatNumbers(List.of(100L, 101L))).isEmpty();
    mockServer.verify();
  }

  @Test
  @DisplayName("성공: 빈 seatIds는 호출 없이 빈 맵을 반환한다")
  void getSeatNumbers_returns_empty_map_without_call_for_empty_input() {
    assertThat(client.getSeatNumbers(List.of())).isEmpty();
    mockServer.verify(); // 등록된 기대가 없으므로 어떤 요청도 나가지 않았음을 검증
  }
}
