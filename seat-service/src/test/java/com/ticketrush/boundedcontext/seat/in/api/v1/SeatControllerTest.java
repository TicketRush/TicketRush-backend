package com.ticketrush.boundedcontext.seat.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutResponse;
import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SeatController.class)
class SeatControllerTest {

  private static final String INTERNAL_TOKEN = "test-token";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SeatFacade seatFacade;

  @Test
  @WithMockUser
  @DisplayName("공연 ID로 전체 좌석 맵 조회를 성공하고 200 OK를 반환한다")
  void getSeatLayouts() throws Exception {
    // given
    Long performanceId = 1L;
    List<SeatLayoutResponse> mockResponse =
        List.of(new SeatLayoutResponse(1L, 101L, "A-1"), new SeatLayoutResponse(2L, 101L, "A-2"));
    given(seatFacade.getPerformanceSeatLayouts(performanceId)).willReturn(mockResponse);

    // when & then
    mockMvc
        .perform(get("/api/v1/seat/{performanceId}/seat-layouts", performanceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isSuccess").value(true))
        .andExpect(jsonPath("$.result.length()").value(2))
        .andExpect(jsonPath("$.result[0].seatId").value(1))
        .andExpect(jsonPath("$.result[0].seatLayoutId").value(101))
        .andExpect(jsonPath("$.result[0].seatNumber").value("A-1"))
        .andExpect(jsonPath("$.result[1].seatId").value(2))
        .andExpect(jsonPath("$.result[1].seatNumber").value("A-2"));
  }

  @Test
  @WithMockUser
  @DisplayName("좌석 판매 확정 요청을 성공하고 200 OK를 반환한다")
  void confirmSold() throws Exception {
    // given
    String requestBody =
        """
        {
          "booking_number": "X7B29-KLPW1",
          "seat_id": 1
        }
        """;

    // when & then
    mockMvc
        .perform(
            post("/api/v1/seat/internal/sold")
                .with(csrf())
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isSuccess").value(true));

    verify(seatFacade).confirmSold("X7B29-KLPW1", 1L);
  }

  @Test
  @WithMockUser
  @DisplayName("좌석 판매 확정 요청에 내부 토큰이 없으면 403 Forbidden을 반환한다")
  void confirmSold_fail_when_internal_token_missing() throws Exception {
    // given
    String requestBody =
        """
        {
          "booking_number": "X7B29-KLPW1",
          "seat_id": 1
        }
        """;

    // when & then
    mockMvc
        .perform(
            post("/api/v1/seat/internal/sold")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isForbidden());
  }
}
