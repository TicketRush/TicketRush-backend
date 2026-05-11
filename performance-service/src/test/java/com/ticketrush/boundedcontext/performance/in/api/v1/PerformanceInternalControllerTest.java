package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PerformanceInternalController.class)
class PerformanceInternalControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PerformanceFacade performanceFacade;

  final String baseUrl = "/api/v1/performance";

  @Test
  @WithMockUser
  @DisplayName("ON_SALE 상태 공연이면 200을 반환한다")
  void validateOnSalePerformance() throws Exception {
    doNothing().when(performanceFacade).validatePerformance(1L);

    mockMvc
        .perform(get(baseUrl + "/1/validate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isSuccess").value(true));
  }

  @Test
  @WithMockUser
  @DisplayName("ON_SALE이 아닌 공연이면 400을 반환한다")
  void validateNotOnSalePerformance() throws Exception {
    doThrow(new BusinessException(ErrorStatus.PERFORMANCE_NOT_ON_SALE))
        .when(performanceFacade)
        .validatePerformance(2L);

    mockMvc
        .perform(get(baseUrl + "/2/validate"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value("PERFORMANCE_400_005"));
  }

  @Test
  @WithMockUser
  @DisplayName("존재하지 않는 공연이면 404를 반환한다")
  void validateNotFoundPerformance() throws Exception {
    doThrow(new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND))
        .when(performanceFacade)
        .validatePerformance(999L);

    mockMvc
        .perform(get(baseUrl + "/999/validate"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value("PERFORMANCE_404_001"));
  }
}
