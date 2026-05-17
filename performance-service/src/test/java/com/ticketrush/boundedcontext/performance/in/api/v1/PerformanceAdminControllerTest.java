package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PerformanceAdminController.class)
@Import({JacksonConfig.class, SecurityConfig.class})
class PerformanceAdminControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PerformanceFacade performanceFacade;

  private static final String BASE_URL = "/api/v1/performance";
  private static final String INTERNAL_TOKEN = "test-token";

  @Test
  @DisplayName("관리자 권한으로 공연 삭제 요청 시 200을 반환한다")
  void deletePerformance_admin_success() throws Exception {
    doNothing().when(performanceFacade).deletePerformance(1L);

    mockMvc
        .perform(
            delete(BASE_URL + "/1")
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("일반 사용자 권한으로 공연 삭제 요청 시 403을 반환한다")
  void deletePerformance_userRole_forbidden() throws Exception {
    mockMvc
        .perform(
            delete(BASE_URL + "/1")
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("인증 없이 공연 삭제 요청 시 403을 반환한다")
  void deletePerformance_noAuth_forbidden() throws Exception {
    mockMvc.perform(delete(BASE_URL + "/1")).andExpect(status().isForbidden());
  }
}
