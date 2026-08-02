package com.ticketrush.boundedcontext.booking.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceInfoResponse;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PerformanceRestClientTest {

  private static final String BASE_URL = "http://localhost:8083";
  private static final long PERFORMANCE_ID = 10L;
  private static final long BULK_BUDGET_MS = 3000;

  private MockRestServiceServer mockServer;
  private PerformanceRestClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();

    client = new PerformanceRestClient(builder.build(), BULK_BUDGET_MS);
  }

  private static String requestUrl(long performanceId) {
    return BASE_URL + "/api/v1/performance/" + performanceId;
  }

  /** performance-service 공통 ApiResponse 형식. 날짜·시간은 JacksonConfig의 실제 직렬 형식을 그대로 쓴다. */
  private static String successBody(long performanceId) {
    return String.join(
        "\n",
        "{",
        "  \"is_success\": true,",
        "  \"code\": \"COMMON_200\",",
        "  \"message\": \"성공입니다.\",",
        "  \"result\": {",
        "    \"performance_id\": " + performanceId + ",",
        "    \"title\": \"오페라의 유령\",",
        "    \"performer\": \"극단 A\",",
        "    \"show_date\": \"2026-05-22\",",
        "    \"show_time\": \"19:30:00\",",
        "    \"price\": 150000,",
        "    \"address\": \"서울 예술의전당 오페라극장\"",
        "  }",
        "}");
  }

  @Test
  @DisplayName("성공: 래핑 응답에서 공연 필드를 파싱한다 — snake_case 키와 날짜·시간 직렬 형식")
  void getPerformance_parses_wrapped_response() {
    mockServer
        .expect(requestTo(requestUrl(PERFORMANCE_ID)))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(successBody(PERFORMANCE_ID), MediaType.APPLICATION_JSON));

    Optional<PerformanceInfoResponse> result = client.getPerformance(PERFORMANCE_ID);

    assertThat(result).isPresent();
    assertThat(result.get().title()).isEqualTo("오페라의 유령");
    assertThat(result.get().showDate()).isEqualTo(LocalDate.of(2026, 5, 22));
    assertThat(result.get().showTime()).isEqualTo(LocalTime.of(19, 30));
    assertThat(result.get().address()).isEqualTo("서울 예술의전당 오페라극장");
    assertThat(result.get().price()).isEqualTo(150000L);
    mockServer.verify();
  }

  @Test
  @DisplayName("성공: 404는 빈 Optional로 수렴한다 — 예외를 밖으로 내지 않는다(부분 응답 정책)")
  void getPerformance_returns_empty_on_404() {
    mockServer
        .expect(requestTo(requestUrl(PERFORMANCE_ID)))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(client.getPerformance(PERFORMANCE_ID)).isEmpty();
    mockServer.verify();
  }

  @Test
  @DisplayName("성공: 5xx도 빈 Optional로 수렴한다")
  void getPerformance_returns_empty_on_5xx() {
    mockServer
        .expect(requestTo(requestUrl(PERFORMANCE_ID)))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withServerError());

    assertThat(client.getPerformance(PERFORMANCE_ID)).isEmpty();
    mockServer.verify();
  }

  @Test
  @DisplayName("성공: 200이지만 result가 비어 있으면 빈 Optional로 수렴한다")
  void getPerformance_returns_empty_on_null_result() {
    mockServer
        .expect(requestTo(requestUrl(PERFORMANCE_ID)))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"is_success\": true, \"code\": \"COMMON_200\", \"result\": null}",
                MediaType.APPLICATION_JSON));

    assertThat(client.getPerformance(PERFORMANCE_ID)).isEmpty();
    mockServer.verify();
  }

  @Test
  @DisplayName("성공: 벌크 조회에서 4xx는 해당 건만 비우고 계속 간다")
  void getPerformances_skips_only_4xx_item() {
    mockServer
        .expect(requestTo(requestUrl(1L)))
        .andRespond(withSuccess(successBody(1L), MediaType.APPLICATION_JSON));
    mockServer.expect(requestTo(requestUrl(2L))).andRespond(withStatus(HttpStatus.NOT_FOUND));
    mockServer
        .expect(requestTo(requestUrl(3L)))
        .andRespond(withSuccess(successBody(3L), MediaType.APPLICATION_JSON));

    Map<Long, PerformanceInfoResponse> result = client.getPerformances(List.of(1L, 2L, 3L));

    assertThat(result).containsOnlyKeys(1L, 3L);
    mockServer.verify();
  }

  @Test
  @DisplayName("성공: 벌크 조회에서 5xx(서비스 장애)는 잔여 호출을 중단한다 — 장애 중 타임아웃이 n번 곱해지지 않게")
  void getPerformances_stops_remaining_on_5xx() {
    mockServer
        .expect(requestTo(requestUrl(1L)))
        .andRespond(withSuccess(successBody(1L), MediaType.APPLICATION_JSON));
    mockServer.expect(requestTo(requestUrl(2L))).andRespond(withServerError());
    // 3번 공연에 대한 기대를 등록하지 않는다 — 요청이 가면 mockServer.verify()가 아니라 호출 시점에 실패한다.

    Map<Long, PerformanceInfoResponse> result = client.getPerformances(List.of(1L, 2L, 3L));

    assertThat(result).containsOnlyKeys(1L);
    mockServer.verify();
  }

  @Test
  @DisplayName("성공: 벽시계 예산이 소진되면 호출 없이 잔여 공연 필드를 비운다 — 느린(예외 없는) 응답에서도 스레드를 놓아준다")
  void getPerformances_stops_when_budget_exhausted() {
    // given — 예산 0이면 첫 건부터 예산 초과다. 기대를 등록하지 않아 요청이 나가면 즉시 실패한다.
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer strictServer = MockRestServiceServer.bindTo(builder).build();
    PerformanceRestClient budgetlessClient = new PerformanceRestClient(builder.build(), 0);

    // when
    Map<Long, PerformanceInfoResponse> result = budgetlessClient.getPerformances(List.of(1L, 2L));

    // then
    assertThat(result).isEmpty();
    strictServer.verify();
  }
}
