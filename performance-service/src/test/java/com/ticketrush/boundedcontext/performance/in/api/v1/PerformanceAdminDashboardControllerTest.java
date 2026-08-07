package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceAdminDashboardResponse;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.support.WebMvcSliceTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcSliceTest(PerformanceAdminDashboardController.class)
@Import({CustomSecurityProperties.class, SecurityConfig.class})
@TestPropertySource(properties = "gateway.internal-token=test-token")
class PerformanceAdminDashboardControllerTest {

  private static final String BASE_URL = "/api/v1/performance/admin/dashboard";
  private static final String INTERNAL_TOKEN = "test-token";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PerformanceFacade performanceFacade;

  @Test
  @DisplayName("관리자 권한으로 대시보드를 조회하면 200을 반환한다")
  void getDashboard_admin_success() throws Exception {
    given(performanceFacade.getAdminDashboard(any(), any())).willReturn(dashboard());

    mockMvc
        .perform(
            get(BASE_URL)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.registered_performances").value(42))
        .andExpect(jsonPath("$.result.average_occupancy_rate").value(0.317));
  }

  @Test
  @DisplayName("집계 필드가 null이어도 대시보드 조회 자체는 200이다")
  void getDashboard_partialFailure_stillSucceeds() throws Exception {
    given(performanceFacade.getAdminDashboard(any(), any()))
        .willReturn(
            new PerformanceAdminDashboardResponse(
                42, null, null, null, null, null, null, null, null, null));

    mockMvc
        .perform(
            get(BASE_URL)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.registered_performances").value(42))
        .andExpect(jsonPath("$.result.total_revenue").doesNotExist());
  }

  @Test
  @DisplayName("기간이 상한을 넘으면 사유를 식별할 수 있는 코드로 400을 반환한다")
  void getDashboard_periodExceedsLimit_badRequest() throws Exception {
    given(performanceFacade.getAdminDashboard(any(), any()))
        .willThrow(new BusinessException(ErrorStatus.PERFORMANCE_DASHBOARD_PERIOD_TOO_LONG));

    mockMvc
        .perform(
            get(BASE_URL)
                .param("from", "2026-01-01")
                .param("to", "2026-12-31")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isBadRequest())
        // 두 거절 사유가 같은 코드로 뭉개지면 프론트가 안내 문구를 만들 수 없다
        .andExpect(jsonPath("$.code").value("PERFORMANCE_400_009"));
  }

  @Test
  @DisplayName("기간이 뒤집히면 상한 초과와 다른 코드로 400을 반환한다")
  void getDashboard_periodReversed_badRequest() throws Exception {
    given(performanceFacade.getAdminDashboard(any(), any()))
        .willThrow(new BusinessException(ErrorStatus.PERFORMANCE_INVALID_DASHBOARD_PERIOD));

    mockMvc
        .perform(
            get(BASE_URL)
                .param("from", "2026-08-07")
                .param("to", "2026-07-09")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PERFORMANCE_400_008"));
  }

  @Test
  @DisplayName("일반 사용자 권한으로 대시보드를 조회하면 403을 반환한다")
  void getDashboard_userRole_forbidden() throws Exception {
    mockMvc
        .perform(
            get(BASE_URL)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("인증 없이 대시보드를 조회하면 403을 반환한다")
  void getDashboard_noAuth_forbidden() throws Exception {
    mockMvc.perform(get(BASE_URL)).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("게이트웨이 토큰 없이 관리자 헤더만 보내면 403을 반환한다")
  void getDashboard_missingGatewayToken_forbidden() throws Exception {
    mockMvc
        .perform(get(BASE_URL).header("X-User-Id", "1").header("X-User-Role", "ADMIN"))
        .andExpect(status().isForbidden());
  }

  private PerformanceAdminDashboardResponse dashboard() {
    return new PerformanceAdminDashboardResponse(
        42, 980L, 147_000_000L, true, 0L, 0.317, 118L, 372L, List.of(), List.of());
  }
}
