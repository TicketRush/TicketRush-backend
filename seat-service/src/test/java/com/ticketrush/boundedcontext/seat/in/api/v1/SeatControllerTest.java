package com.ticketrush.boundedcontext.seat.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAvailabilityResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutResponse;
import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.support.WebMvcSliceTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcSliceTest(SeatController.class)
class SeatControllerTest {

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
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.length()").value(2))
        .andExpect(jsonPath("$.result[0].seat_id").value(1))
        .andExpect(jsonPath("$.result[0].seat_layout_id").value(101))
        .andExpect(jsonPath("$.result[0].seat_number").value("A-1"))
        .andExpect(jsonPath("$.result[1].seat_id").value(2))
        .andExpect(jsonPath("$.result[1].seat_number").value("A-2"));
  }

  @Test
  @WithMockUser
  @DisplayName("공연 ID로 잔여 좌석 수와 전체 좌석 수를 조회하고 200 OK를 반환한다")
  void getSeatCounts() throws Exception {
    // given
    Long performanceId = 1L;
    SeatAvailabilityResponse response = new SeatAvailabilityResponse(8L, 10L);
    given(seatFacade.getPerformanceSeatAvailability(performanceId)).willReturn(response);

    // when & then
    mockMvc
        .perform(get("/api/v1/seat/{performanceId}/seat-counts", performanceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.available_count").value(8))
        .andExpect(jsonPath("$.result.total_count").value(10));
  }
}
