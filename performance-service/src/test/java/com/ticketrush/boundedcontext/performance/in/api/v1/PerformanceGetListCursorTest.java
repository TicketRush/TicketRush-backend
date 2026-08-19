package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.dto.request.CursorPageRequest;
import com.ticketrush.support.WebMvcSliceTest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcSliceTest(PerformanceController.class)
@Import({CustomSecurityProperties.class, SecurityConfig.class})
class PerformanceGetListCursorTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PerformanceFacade performanceFacade;

  /** 좌석 수를 모르는 공연 (좌석 서비스 조회 실패 또는 좌석 미생성). */
  private PerformanceListResponse sampleResponse(Long performanceId) {
    return sampleResponse(performanceId, null, null);
  }

  private PerformanceListResponse sampleResponse(
      Long performanceId, Long totalSeats, Long remainingSeats) {
    return new PerformanceListResponse(
        performanceId,
        "공연명",
        "가수",
        Genre.CONCERT,
        LocalDate.of(2026, 9, 1),
        LocalTime.of(19, 0),
        "서울",
        "https://s3.example.com/main.jpg",
        PerformanceStatus.ON_SALE,
        50000L,
        totalSeats,
        remainingSeats);
  }

  @Test
  @DisplayName("좌석 수를 아는 공연은 게이지바 필드가 snake_case로 함께 내려간다")
  void getPerformances_exposesSeatFieldsInSnakeCase() throws Exception {
    when(performanceFacade.getPerformances(
            isNull(), isNull(), isNull(), isNull(), any(CursorPageRequest.class)))
        .thenReturn(
            new SliceImpl<>(
                List.of(sampleResponse(10L, 500L, 245L)), PageRequest.of(0, 10), false));

    mockMvc
        .perform(get("/api/v1/performance"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result[0].total_seats").value(500))
        .andExpect(jsonPath("$.result[0].remaining_seats").value(245));
  }

  @Test
  @DisplayName("좌석 수를 모르면 200으로 응답하되 좌석 키가 응답에서 아예 빠진다")
  void getPerformances_unknownSeatCounts_omitsSeatKeys() throws Exception {
    when(performanceFacade.getPerformances(
            isNull(), isNull(), isNull(), isNull(), any(CursorPageRequest.class)))
        .thenReturn(new SliceImpl<>(List.of(sampleResponse(10L)), PageRequest.of(0, 10), false));

    mockMvc
        .perform(get("/api/v1/performance"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result[0].performance_id").value(10))
        .andExpect(jsonPath("$.result[0].total_seats").doesNotExist())
        .andExpect(jsonPath("$.result[0].remaining_seats").doesNotExist());
  }

  @Test
  @DisplayName("다음 페이지가 있으면 마지막 원소의 performanceId가 next_cursor로 반환된다")
  void getPerformances_returnsNextCursorOfLastElement() throws Exception {
    when(performanceFacade.getPerformances(
            isNull(), isNull(), isNull(), isNull(), any(CursorPageRequest.class)))
        .thenReturn(
            new SliceImpl<>(
                List.of(sampleResponse(10L), sampleResponse(9L)), PageRequest.of(0, 2), true));

    mockMvc
        .perform(get("/api/v1/performance").param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.pagination_info.has_next").value(true))
        .andExpect(jsonPath("$.pagination_info.next_cursor").value(9))
        .andExpect(jsonPath("$.pagination_info.size").value(2))
        .andExpect(jsonPath("$.result[0].performance_id").value(10))
        .andExpect(jsonPath("$.result[1].performance_id").value(9));
  }

  @Test
  @DisplayName("마지막 페이지면 has_next가 false이고 next_cursor는 반환되지 않는다")
  void getPerformances_lastPage_noNextCursor() throws Exception {
    when(performanceFacade.getPerformances(
            isNull(), isNull(), isNull(), isNull(), any(CursorPageRequest.class)))
        .thenReturn(new SliceImpl<>(List.of(sampleResponse(1L)), PageRequest.of(0, 10), false));

    mockMvc
        .perform(get("/api/v1/performance"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination_info.has_next").value(false))
        .andExpect(jsonPath("$.pagination_info.next_cursor").doesNotExist());
  }

  @Test
  @DisplayName("cursorId와 size 쿼리 파라미터가 CursorPageRequest로 바인딩된다")
  void getPerformances_bindsCursorPageRequest() throws Exception {
    when(performanceFacade.getPerformances(
            isNull(), isNull(), isNull(), isNull(), any(CursorPageRequest.class)))
        .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 2), false));

    mockMvc
        .perform(get("/api/v1/performance").param("cursorId", "5").param("size", "2"))
        .andExpect(status().isOk());

    ArgumentCaptor<CursorPageRequest> captor = ArgumentCaptor.forClass(CursorPageRequest.class);
    verify(performanceFacade)
        .getPerformances(isNull(), isNull(), isNull(), isNull(), captor.capture());
    assertThat(captor.getValue().cursorId()).isEqualTo(5L);
    assertThat(captor.getValue().size()).isEqualTo(2);
  }
}
