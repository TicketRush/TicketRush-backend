package com.ticketrush.boundedcontext.seat.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsByPerformanceResponse;
import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SeatInternalController.class)
@Import({
  JacksonConfig.class,
  SecurityConfig.class,
  GatewayHeaderFilter.class,
  CustomSecurityProperties.class
})
@TestPropertySource(
    properties = {
      "gateway.internal-token=test-token",
      "custom.security.internal-token=test-internal-token"
    })
class SeatInternalControllerTest {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
  private static final String SEAT_COUNTS_URL = "/api/v1/internal/seat/seat-counts";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SeatFacade seatFacade;

  /**
   * 이 테스트가 고정하는 것은 값이 아니라 <b>JSON 키 이름</b>이다. performance-service의 클라이언트가 이 키로 매핑하는데, 그쪽은 앱의
   * SNAKE_CASE 설정을 타지 않는 {@code RestClient.builder()}라 키가 어긋나도 예외 없이 조용히 0이 된다. 그러면 전 공연 점유율 0%가 정상
   * 응답처럼 보인다 — 소비자 쪽 테스트만으로는 여기서 이름이 바뀌는 것을 잡지 못한다.
   */
  @Test
  @DisplayName("성공: 전 공연 좌석 수를 snake_case 키로 200 반환한다 (#563 대시보드 계약)")
  void getAllSeatCounts_success() throws Exception {
    // given
    given(seatFacade.getAllPerformanceSeatStatusCounts(null))
        .willReturn(
            List.of(
                new SeatStatusCountsByPerformanceResponse(100L, 120L, 82L, 38L, 0L),
                new SeatStatusCountsByPerformanceResponse(200L, 80L, 70L, 10L, 0L)));

    // when & then
    mockMvc
        .perform(get(SEAT_COUNTS_URL).header(INTERNAL_TOKEN_HEADER, "test-internal-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result[0].performance_id").value(100))
        .andExpect(jsonPath("$.result[0].total_count").value(120))
        .andExpect(jsonPath("$.result[0].available_count").value(82))
        .andExpect(jsonPath("$.result[0].sold_count").value(38))
        .andExpect(jsonPath("$.result[0].hold_count").value(0))
        .andExpect(jsonPath("$.result[1].performance_id").value(200));
  }

  @Test
  @DisplayName("성공: 좌석이 하나도 없으면 빈 배열을 반환한다")
  void getAllSeatCounts_empty() throws Exception {
    // given
    given(seatFacade.getAllPerformanceSeatStatusCounts(null)).willReturn(List.of());

    // when & then
    mockMvc
        .perform(get(SEAT_COUNTS_URL).header(INTERNAL_TOKEN_HEADER, "test-internal-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").isArray())
        .andExpect(jsonPath("$.result").isEmpty());
  }

  @Test
  @DisplayName("성공: performance_ids를 주면 그 공연들만 조회한다 (#590 관리자 공연 목록)")
  void getAllSeatCounts_withPerformanceIds() throws Exception {
    // given: 클라이언트가 콤마로 이어 보낸 값이 List<Long>으로 바인딩되는지까지 함께 고정한다
    given(seatFacade.getAllPerformanceSeatStatusCounts(List.of(100L, 200L)))
        .willReturn(List.of(new SeatStatusCountsByPerformanceResponse(100L, 500L, 462L, 38L, 0L)));

    // when & then
    mockMvc
        .perform(
            get(SEAT_COUNTS_URL)
                .param("performanceIds", "100,200")
                .header(INTERNAL_TOKEN_HEADER, "test-internal-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result[0].performance_id").value(100))
        .andExpect(jsonPath("$.result[0].total_count").value(500))
        .andExpect(jsonPath("$.result[1]").doesNotExist());
  }

  @Test
  @DisplayName("실패: performance_ids가 상한을 넘으면 400을 반환한다")
  void getAllSeatCounts_tooManyPerformanceIds_badRequest() throws Exception {
    // given: @Validated가 없으면 이 케이스가 500이 되고, 호출자 fail-open이 그 500을 장애로 오인한다
    String tooMany =
        LongStream.rangeClosed(1, 121).mapToObj(String::valueOf).collect(Collectors.joining(","));

    // when & then
    mockMvc
        .perform(
            get(SEAT_COUNTS_URL)
                .param("performanceIds", tooMany)
                .header(INTERNAL_TOKEN_HEADER, "test-internal-token"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(seatFacade);
  }

  @Test
  @DisplayName("실패: 내부 토큰이 일치하지 않으면 403을 반환한다")
  void getAllSeatCounts_tokenMismatch_forbidden() throws Exception {
    mockMvc
        .perform(get(SEAT_COUNTS_URL).header(INTERNAL_TOKEN_HEADER, "wrong"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(seatFacade);
  }

  @Test
  @DisplayName("실패: 내부 토큰이 없으면 403을 반환한다")
  void getAllSeatCounts_tokenMissing_forbidden() throws Exception {
    mockMvc.perform(get(SEAT_COUNTS_URL)).andExpect(status().isForbidden());

    verifyNoInteractions(seatFacade);
  }
}
