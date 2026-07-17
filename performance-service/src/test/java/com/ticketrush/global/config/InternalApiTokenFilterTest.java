package com.ticketrush.global.config;

import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.boundedcontext.performance.in.api.v1.PerformanceInternalController;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.support.WebMvcSliceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcSliceTest(PerformanceInternalController.class)
@Import({SecurityConfig.class, GatewayHeaderFilter.class, CustomSecurityProperties.class})
@TestPropertySource(
    properties = {
      "custom.security.internal-token=test-internal-token",
      "custom.security.permit-all=false"
    })
class InternalApiTokenFilterTest {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PerformanceFacade performanceFacade;

  @Test
  @DisplayName("내부 API는 내부 토큰이 없으면 403 Forbidden을 반환한다")
  void internalApi_withoutToken_forbidden() throws Exception {
    mockMvc
        .perform(get("/api/v1/internal/performance/1/validate"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("내부 API는 내부 토큰이 틀리면 403 Forbidden을 반환한다")
  void internalApi_withWrongToken_forbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/internal/performance/1/validate")
                .header(INTERNAL_TOKEN_HEADER, "wrong-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("내부 API는 내부 토큰이 맞으면 요청을 통과시킨다")
  void internalApi_withValidToken_success() throws Exception {
    doNothing().when(performanceFacade).validatePerformance(1L);

    mockMvc
        .perform(
            get("/api/v1/internal/performance/1/validate")
                .header(INTERNAL_TOKEN_HEADER, "test-internal-token"))
        .andExpect(status().isOk());
  }
}
