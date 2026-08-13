package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PerformanceAdminController.class)
@Import({CustomSecurityProperties.class, JacksonConfig.class, SecurityConfig.class})
@TestPropertySource(properties = "gateway.internal-token=test-token")
class PerformanceAdminControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PerformanceFacade performanceFacade;

  private static final String BASE_URL = "/api/v1/performance/admin";
  private static final String INTERNAL_TOKEN = "test-token";

  @Test
  @DisplayName("관리자 권한으로 공연 목록을 조회하면 200을 반환한다")
  void getAdminPerformances_admin_success() throws Exception {
    given(performanceFacade.getAdminPerformances(any()))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

    mockMvc
        .perform(
            get(BASE_URL)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("일반 사용자 권한으로 공연 목록을 조회하면 403을 반환한다")
  void getAdminPerformances_userRole_forbidden() throws Exception {
    mockMvc
        .perform(
            get(BASE_URL)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("인증 없이 공연 목록을 조회하면 403을 반환한다")
  void getAdminPerformances_noAuth_forbidden() throws Exception {
    mockMvc.perform(get(BASE_URL)).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("관리자 권한으로 공연 삭제 요청 시 200을 반환한다")
  void deletePerformance_admin_success() throws Exception {
    doNothing().when(performanceFacade).deletePerformance(1L);

    mockMvc
        .perform(
            delete(BASE_URL + "/1")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
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
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("인증 없이 공연 삭제 요청 시 403을 반환한다")
  void deletePerformance_noAuth_forbidden() throws Exception {
    mockMvc.perform(delete(BASE_URL + "/1")).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("내부 토큰 없이 관리자 권한으로 공연 삭제 요청 시 403을 반환한다")
  void deletePerformance_missingInternalToken_forbidden() throws Exception {
    mockMvc
        .perform(delete(BASE_URL + "/1").header("X-User-Id", "1").header("X-User-Role", "ADMIN"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("잘못된 내부 토큰과 관리자 권한으로 공연 삭제 요청 시 403을 반환한다")
  void deletePerformance_invalidInternalToken_forbidden() throws Exception {
    mockMvc
        .perform(
            delete(BASE_URL + "/1")
                .header("X-Gateway-Token", "invalid-token")
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("관리자 권한으로 예매 오픈 시각 해제 요청 시 200을 반환한다")
  void clearBookingOpenAt_admin_success() throws Exception {
    doNothing().when(performanceFacade).clearBookingOpenAt(1L);

    mockMvc
        .perform(
            delete(BASE_URL + "/1/booking-open-at")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("일반 사용자 권한으로 예매 오픈 시각 해제 요청 시 403을 반환한다")
  void clearBookingOpenAt_userRole_forbidden() throws Exception {
    mockMvc
        .perform(
            delete(BASE_URL + "/1/booking-open-at")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", "1")
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("인증 없이 예매 오픈 시각 해제 요청 시 403을 반환한다")
  void clearBookingOpenAt_noAuth_forbidden() throws Exception {
    mockMvc.perform(delete(BASE_URL + "/1/booking-open-at")).andExpect(status().isForbidden());
  }
}
