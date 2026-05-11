package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceDetailResponse;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PerformanceController.class)
class PerformanceGetDetailTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PerformanceFacade performanceFacade;

  final String baseUrl = "/api/v1/performance";

  @Test
  @WithMockUser
  @DisplayName("공연 ID로 상세 조회 시 200과 상세 데이터를 반환한다")
  void getPerformanceDetail_success() throws Exception {
    PerformanceDetailResponse response =
        new PerformanceDetailResponse(
            1L,
            "레미제라블",
            "홍길동",
            Genre.MUSICAL,
            "공연 안내 내용",
            LocalDate.of(2025, 9, 1),
            LocalTime.of(19, 0),
            150,
            80000L,
            500,
            "서울특별시 중구 세종대로 110",
            PerformanceStatus.ON_SALE,
            "https://s3.example.com/main.jpg",
            "https://s3.example.com/model.glb",
            List.of("https://s3.example.com/gallery1.jpg"),
            List.of("주차장", "수유실"));

    when(performanceFacade.getPerformanceDetail(1L)).thenReturn(response);

    mockMvc
        .perform(get(baseUrl + "/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isSuccess").value(true))
        .andExpect(jsonPath("$.result.performanceId").value(1))
        .andExpect(jsonPath("$.result.title").value("레미제라블"))
        .andExpect(jsonPath("$.result.genre").value("MUSICAL"))
        .andExpect(jsonPath("$.result.facilities[0]").value("주차장"));
  }

  @Test
  @WithMockUser
  @DisplayName("존재하지 않는 공연 ID 조회 시 404를 반환한다")
  void getPerformanceDetail_notFound() throws Exception {
    doThrow(new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND))
        .when(performanceFacade)
        .getPerformanceDetail(999L);

    mockMvc
        .perform(get(baseUrl + "/999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value("PERFORMANCE_404_001"));
  }
}
