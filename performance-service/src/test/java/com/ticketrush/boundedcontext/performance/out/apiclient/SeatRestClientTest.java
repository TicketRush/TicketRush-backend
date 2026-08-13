package com.ticketrush.boundedcontext.performance.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ticketrush.boundedcontext.performance.out.apiclient.dto.SeatCountsInfo;
import com.ticketrush.global.config.CustomSecurityProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SeatRestClientTest {

  private static final String BASE_URL = "http://seat-service:8086";
  private static final String URI = BASE_URL + "/api/v1/internal/seat/seat-counts";

  private MockRestServiceServer server;
  private SeatRestClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();

    CustomSecurityProperties properties = new CustomSecurityProperties();
    properties.setInternalToken("test-internal-token");

    client = new SeatRestClient(builder.build(), properties, BASE_URL);
  }

  @Test
  @DisplayName("snake_case 응답을 공연 ID로 색인해 반환한다")
  void getSeatCounts_IndexesByPerformanceId() {
    // given
    server
        .expect(requestTo(URI))
        .andExpect(header("X-Internal-Token", "test-internal-token"))
        .andRespond(
            withSuccess(
                """
                {
                  "code": "COMMON_200",
                  "result": [
                    {"performance_id": 100, "total_count": 120, "available_count": 82,
                     "sold_count": 38, "hold_count": 0},
                    {"performance_id": 200, "total_count": 80, "available_count": 70,
                     "sold_count": 10, "hold_count": 0}
                  ]
                }
                """,
                MediaType.APPLICATION_JSON));

    // when
    Map<Long, SeatCountsInfo> result = client.getSeatCounts();

    // then
    assertThat(result).hasSize(2);
    assertThat(result.get(100L).totalCount()).isEqualTo(120L);
    assertThat(result.get(100L).soldCount()).isEqualTo(38L);
    assertThat(result.get(200L).totalCount()).isEqualTo(80L);
    server.verify();
  }

  @Test
  @DisplayName("좌석 서비스가 5xx를 내면 빈 맵으로 수렴한다")
  void getSeatCounts_WhenServerError_ReturnsEmptyMap() {
    // given
    server.expect(requestTo(URI)).andRespond(withServerError());

    // when
    Map<Long, SeatCountsInfo> result = client.getSeatCounts();

    // then: 빈 맵은 '좌석 0석'이 아니라 '모름'이며, 호출자가 점유율을 null로 내린다
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("URL이 http(s) 절대 주소가 아니면 호출하지 않는다")
  void getSeatCounts_WhenUrlUnusable_SkipsCall() {
    // given: 스킴이 빠진 값. 그대로 요청하면 IllegalArgumentException이라 fail-open catch를 뚫는다.
    RestClient.Builder builder = RestClient.builder().baseUrl("seat-service:8086");
    MockRestServiceServer strictServer = MockRestServiceServer.bindTo(builder).build();
    SeatRestClient misconfigured =
        new SeatRestClient(builder.build(), new CustomSecurityProperties(), "seat-service:8086");

    // when & then
    assertThat(misconfigured.getSeatCounts()).isEmpty();
    strictServer.verify();
  }

  @Test
  @DisplayName("수치 키가 누락되면 0으로 읽지 않고 빈 맵으로 수렴한다")
  void getSeatCounts_WhenNumericKeyMissing_FallsBackToEmpty() {
    // given: sold_count가 통째로 빠진 응답. 생산자 필드가 null이 되면 전역 NON_NULL 설정 때문에
    // 키가 통째로 사라지므로, 이론이 아니라 실제로 일어날 수 있는 형태다.
    server
        .expect(requestTo(URI))
        .andRespond(
            withSuccess(
                """
                {"result": [{"performance_id": 100, "total_count": 120}]}
                """,
                MediaType.APPLICATION_JSON));

    // when
    Map<Long, SeatCountsInfo> result = client.getSeatCounts();

    // then: 소비자 필드가 원시 long이라 "판매 0석"으로 조용히 읽힐 것을 우려했으나, 실제로는 record의 원시
    // 파라미터를 채우지 못해 역직렬화가 실패하고 fail-open 경로로 떨어진다. 즉 결과는 "0%"가 아니라 "모름"이며,
    // 그 편이 이 API의 계약과 맞다. 원시 타입을 박스 타입으로 올리는 것을 이번 범위에서 미룬 근거이기도 하다.
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("공연 ID가 중복된 응답이 와도 예외 없이 마지막 값으로 접는다")
  void getSeatCounts_WhenDuplicateIds_DoesNotThrow() {
    // given: toMap()을 썼다면 IllegalStateException이 나고, 그건 RestClientException이 아니라 catch를 뚫는다
    server
        .expect(requestTo(URI))
        .andRespond(
            withSuccess(
                """
                {
                  "result": [
                    {"performance_id": 100, "total_count": 120, "sold_count": 38},
                    {"performance_id": 100, "total_count": 120, "sold_count": 40}
                  ]
                }
                """,
                MediaType.APPLICATION_JSON));

    // when
    Map<Long, SeatCountsInfo> result = client.getSeatCounts();

    // then
    assertThat(result).hasSize(1);
    assertThat(result.get(100L).soldCount()).isEqualTo(40L);
  }
}
