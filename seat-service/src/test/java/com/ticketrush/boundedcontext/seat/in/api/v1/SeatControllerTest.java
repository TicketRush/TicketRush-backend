package com.ticketrush.boundedcontext.seat.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatNumberResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsResponse;
import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.support.WebMvcSliceTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcSliceTest(SeatController.class)
@Import({SecurityConfig.class, CustomSecurityProperties.class, GatewayHeaderFilter.class})
@TestPropertySource(properties = "gateway.internal-token=test-token")
class SeatControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SeatFacade seatFacade;

  @Test
  @WithMockUser
  @DisplayName("공연 ID로 전체 좌석 맵 조회를 성공하고 200 OK를 반환한다")
  void getSeatMap() throws Exception {
    // given: 파사드가 캐시/직렬화해 돌려주는 형태 그대로의 snake_case JSON 배열(#469).
    // RawValue 스플라이스가 이 문자열을 이스케이프된 String이 아니라 실제 JSON 배열로 내보내는지가
    // 아래 jsonPath 단언의 핵심이다 — 기존 응답 형태 회귀 방지.
    Long performanceId = 1L;
    String seatMapJson =
        "[{\"seat_id\":1,\"seat_layout_id\":101,"
            + "\"seat_number\":\"A-1\",\"seat_status\":\"AVAILABLE\"},"
            + "{\"seat_id\":2,\"seat_layout_id\":101,"
            + "\"seat_number\":\"A-2\",\"seat_status\":\"HOLD\"}]";
    given(seatFacade.getPerformanceSeatMap(performanceId)).willReturn(seatMapJson);

    // when & then
    mockMvc
        .perform(get("/api/v1/seat/{performanceId}/seat-layouts", performanceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.length()").value(2))
        .andExpect(jsonPath("$.result[0].seat_id").value(1))
        .andExpect(jsonPath("$.result[0].seat_layout_id").value(101))
        .andExpect(jsonPath("$.result[0].seat_number").value("A-1"))
        .andExpect(jsonPath("$.result[0].seat_status").value("AVAILABLE"))
        .andExpect(jsonPath("$.result[1].seat_id").value(2))
        .andExpect(jsonPath("$.result[1].seat_number").value("A-2"))
        .andExpect(jsonPath("$.result[1].seat_status").value("HOLD"));
  }

  @Test
  @WithMockUser
  @DisplayName("좌석 ID 목록으로 좌석 번호 목록을 조회한다")
  void getSeatNumbers() throws Exception {
    // given
    List<Long> seatIds = List.of(1L, 2L);
    List<SeatNumberResponse> response =
        List.of(new SeatNumberResponse(1L, "A-1"), new SeatNumberResponse(2L, "A-2"));
    given(seatFacade.getSeatNumbers(seatIds)).willReturn(response);

    // when & then
    mockMvc
        .perform(get("/api/v1/seat/numbers").param("seatIds", "1", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result[0].seat_id").value(1))
        .andExpect(jsonPath("$.result[0].seat_number").value("A-1"))
        .andExpect(jsonPath("$.result[1].seat_id").value(2))
        .andExpect(jsonPath("$.result[1].seat_number").value("A-2"));

    verify(seatFacade).getSeatNumbers(seatIds);
  }

  @Test
  @WithMockUser
  @DisplayName("공연 ID로 좌석 상태 SSE 스트림을 구독한다")
  void subscribeSeatStatus() throws Exception {
    // given
    Long performanceId = 1L;
    SseEmitter emitter = new SseEmitter();
    given(seatFacade.subscribeSeatStatus(performanceId)).willReturn(emitter);

    // when & then
    mockMvc
        .perform(get("/api/v1/seat/{performanceId}/seat-status/stream", performanceId))
        .andExpect(status().isOk());

    verify(seatFacade).subscribeSeatStatus(performanceId);
  }

  @Test
  @WithMockUser
  @DisplayName("공연 ID로 전체 좌석 수와 상태별 좌석 수를 조회하고 200 OK를 반환한다")
  void getSeatCounts() throws Exception {
    // given
    Long performanceId = 1L;
    SeatStatusCountsResponse response = new SeatStatusCountsResponse(10L, 6L, 3L, 1L);
    given(seatFacade.getPerformanceSeatStatusCounts(performanceId)).willReturn(response);

    // when & then
    mockMvc
        .perform(get("/api/v1/seat/{performanceId}/seat-counts", performanceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.total_count").value(10))
        .andExpect(jsonPath("$.result.available_count").value(6))
        .andExpect(jsonPath("$.result.sold_count").value(3))
        .andExpect(jsonPath("$.result.hold_count").value(1));
  }
}
