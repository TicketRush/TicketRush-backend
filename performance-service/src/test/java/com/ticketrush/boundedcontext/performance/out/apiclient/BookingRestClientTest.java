package com.ticketrush.boundedcontext.performance.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ticketrush.boundedcontext.performance.out.apiclient.dto.BookingStatsInfo;
import com.ticketrush.global.config.CustomSecurityProperties;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 이 클라이언트는 {@code RestClient.builder()}로 만들어져 앱의 {@code JacksonConfig}(SNAKE_CASE)를 타지 않는다. 응답 키가
 * 하나라도 어긋나면 예외 없이 <b>조용히 0/null</b>이 되므로, 실제 응답 형태로 매핑을 고정한다.
 */
class BookingRestClientTest {

  private static final String BASE_URL = "http://booking-service:8084";
  private static final LocalDate FROM = LocalDate.of(2026, 7, 9);
  private static final LocalDate TO = LocalDate.of(2026, 8, 7);

  private MockRestServiceServer server;
  private BookingRestClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();

    CustomSecurityProperties properties = new CustomSecurityProperties();
    properties.setInternalToken("test-internal-token");

    client = new BookingRestClient(builder.build(), properties, BASE_URL);
  }

  @Test
  @DisplayName("snake_case 응답을 필드 손실 없이 매핑하고 내부 토큰을 싣는다")
  void getStats_MapsSnakeCaseResponse() {
    // given
    server
        .expect(
            requestTo(BASE_URL + "/api/v1/internal/booking/stats?from=2026-07-09&to=2026-08-07"))
        .andExpect(header("X-Internal-Token", "test-internal-token"))
        .andRespond(
            withSuccess(
                """
                {
                  "code": "COMMON_200",
                  "result": {
                    "summary": {
                      "total_bookings": 1250,
                      "completed_bookings": 980,
                      "canceled_bookings": 120,
                      "total_revenue": 147000000,
                      "revenue_complete": false,
                      "missing_amount_bookings": 3
                    },
                    "by_performance": [
                      {"performance_id": 100, "confirmed_count": 30, "confirmed_revenue": 5000000}
                    ],
                    "by_date": [
                      {"date": "2026-08-01", "revenue": 1000000}
                    ]
                  }
                }
                """,
                MediaType.APPLICATION_JSON));

    // when
    Optional<BookingStatsInfo> result = client.getStats(FROM, TO);

    // then
    assertThat(result).isPresent();
    BookingStatsInfo stats = result.get();
    assertThat(stats.summary().completedBookings()).isEqualTo(980L);
    assertThat(stats.summary().totalRevenue()).isEqualTo(147_000_000L);
    assertThat(stats.summary().revenueComplete()).isFalse();
    assertThat(stats.summary().missingAmountBookings()).isEqualTo(3L);
    assertThat(stats.byPerformance()).hasSize(1);
    assertThat(stats.byPerformance().get(0).performanceId()).isEqualTo(100L);
    assertThat(stats.byPerformance().get(0).confirmedRevenue()).isEqualTo(5_000_000L);
    assertThat(stats.byDate()).hasSize(1);
    assertThat(stats.byDate().get(0).date()).isEqualTo(LocalDate.of(2026, 8, 1));
    assertThat(stats.byDate().get(0).revenue()).isEqualTo(1_000_000L);
    server.verify();
  }

  @Test
  @DisplayName("예매 서비스가 5xx를 내면 빈 값으로 수렴하고 예외를 밖으로 내지 않는다")
  void getStats_WhenServerError_ReturnsEmpty() {
    // given
    server
        .expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
        .andRespond(withServerError());

    // when
    Optional<BookingStatsInfo> result = client.getStats(FROM, TO);

    // then: 여기서 던지면 예매 서비스 장애가 대시보드 전체를 죽인다
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("스킴이 빠진 URL이면 호출하지 않는다 — 그대로 두면 fail-open catch를 뚫고 500이 된다")
  void getStats_WhenUrlHasNoScheme_SkipsCall() {
    // given: compose 서비스명을 그대로 넣은 흔한 오설정. 문자열로는 멀쩡해 hasText 검사는 통과한다.
    RestClient.Builder builder = RestClient.builder().baseUrl("booking-service:8084");
    MockRestServiceServer strictServer = MockRestServiceServer.bindTo(builder).build();
    BookingRestClient misconfigured =
        new BookingRestClient(
            builder.build(), new CustomSecurityProperties(), "booking-service:8084");

    // when
    Optional<BookingStatsInfo> result = misconfigured.getStats(FROM, TO);

    // then: 요청이 나갔다면 IllegalArgumentException(스킴 없는 URI)이 되어 catch를 뚫었을 것이다
    assertThat(result).isEmpty();
    strictServer.verify();
  }

  @Test
  @DisplayName("URL이 설정되지 않으면 호출을 시도하지 않고 빈 값을 반환한다")
  void getStats_WhenUrlNotConfigured_SkipsCall() {
    // given: baseUrl이 빈 채로 요청이 나가면 스킴 없는 URI라 RestClientException이 아닌 예외가 되어 catch를 뚫는다
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer strictServer = MockRestServiceServer.bindTo(builder).build();
    BookingRestClient unconfigured =
        new BookingRestClient(builder.build(), new CustomSecurityProperties(), "");

    // when
    Optional<BookingStatsInfo> result = unconfigured.getStats(FROM, TO);

    // then
    assertThat(result).isEmpty();
    strictServer.verify(); // 기대한 요청이 없었음을 확인
  }
}
