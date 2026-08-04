package com.ticketrush.boundedcontext.seat.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAdminMonitoringResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAdminSeatDetailResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatMapItemResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsResponse;
import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.types.SeatStatus;
import com.ticketrush.support.WebMvcSliceTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcSliceTest(SeatAdminController.class)
@Import({SecurityConfig.class, CustomSecurityProperties.class, GatewayHeaderFilter.class})
@TestPropertySource(properties = "gateway.internal-token=test-token")
class SeatAdminControllerTest {

  private static final String GATEWAY_TOKEN = "test-token";
  private static final Long PERFORMANCE_ID = 1L;
  private static final Long SEAT_ID = 100L;
  private static final Long ADMIN_ID = 42L;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SeatFacade seatFacade;

  private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
    return request
        .header("X-Gateway-Token", GATEWAY_TOKEN)
        .header("X-User-Id", ADMIN_ID)
        .header("X-User-Role", "ADMIN");
  }

  private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
    return request
        .header("X-Gateway-Token", GATEWAY_TOKEN)
        .header("X-User-Id", 7L)
        .header("X-User-Role", "USER");
  }

  @Test
  @DisplayName("ADMIN이 좌석 현황을 조회하면 요약 4종과 좌석 맵이 함께 응답된다")
  void getMonitoring_returns_summary_and_seat_map() throws Exception {
    // given
    given(seatFacade.getAdminMonitoring(PERFORMANCE_ID))
        .willReturn(
            new SeatAdminMonitoringResponse(
                new SeatStatusCountsResponse(100L, 80L, 15L, 5L),
                List.of(
                    new SeatMapItemResponse(
                        SEAT_ID,
                        101L,
                        "A-1",
                        SeatStatus.HOLD,
                        LocalDateTime.of(2026, 5, 22, 10, 35)))));

    // when & then
    mockMvc
        .perform(asAdmin(get("/api/v1/seat/admin/{performanceId}/monitoring", PERFORMANCE_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.summary.total_count").value(100))
        .andExpect(jsonPath("$.result.summary.available_count").value(80))
        .andExpect(jsonPath("$.result.summary.sold_count").value(15))
        .andExpect(jsonPath("$.result.summary.hold_count").value(5))
        .andExpect(jsonPath("$.result.seats.length()").value(1))
        .andExpect(jsonPath("$.result.seats[0].seat_id").value(100))
        .andExpect(jsonPath("$.result.seats[0].seat_number").value("A-1"))
        .andExpect(jsonPath("$.result.seats[0].seat_status").value("HOLD"));

    verify(seatFacade).getAdminMonitoring(PERFORMANCE_ID);
  }

  @Test
  @DisplayName("ADMIN이 좌석 단건을 조회하면 선점 시각과 남은 시간이 응답된다")
  void getSeatDetail_returns_hold_window() throws Exception {
    // given
    given(seatFacade.getAdminSeatDetail(PERFORMANCE_ID, SEAT_ID))
        .willReturn(
            new SeatAdminSeatDetailResponse(
                SEAT_ID,
                "A-1",
                SeatStatus.HOLD,
                "X7B29-KLPW1",
                LocalDateTime.of(2026, 5, 22, 10, 30),
                LocalDateTime.of(2026, 5, 22, 10, 35),
                212L));

    // when & then
    mockMvc
        .perform(
            asAdmin(get("/api/v1/seat/admin/{performanceId}/{seatId}", PERFORMANCE_ID, SEAT_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.seat_number").value("A-1"))
        .andExpect(jsonPath("$.result.seat_status").value("HOLD"))
        .andExpect(jsonPath("$.result.booking_number").value("X7B29-KLPW1"))
        .andExpect(jsonPath("$.result.hold_started_at").value("2026-05-22 10:30:00"))
        .andExpect(jsonPath("$.result.hold_expired_at").value("2026-05-22 10:35:00"))
        .andExpect(jsonPath("$.result.remaining_seconds").value(212));
  }

  @Test
  @DisplayName("모니터링 경로가 좌석 단건 조회의 경로 변수보다 우선한다")
  void monitoringPath_takes_precedence_over_seat_id_variable() throws Exception {
    // given: /{performanceId}/{seatId}가 /{performanceId}/monitoring을 삼킬 수 있다.
    // Spring의 리터럴 우선 매칭에 의존하고 있으므로 그 전제를 여기서 고정한다 (#562).
    given(seatFacade.getAdminMonitoring(PERFORMANCE_ID))
        .willReturn(
            new SeatAdminMonitoringResponse(
                new SeatStatusCountsResponse(0L, 0L, 0L, 0L), List.of()));

    // when & then
    mockMvc
        .perform(asAdmin(get("/api/v1/seat/admin/{performanceId}/monitoring", PERFORMANCE_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.summary").exists());

    verify(seatFacade).getAdminMonitoring(PERFORMANCE_ID);
  }

  @Test
  @DisplayName("ADMIN이 강제 해제를 요청하면 처리자 ID와 함께 파사드로 위임된다")
  void forceReleaseHold_delegates_with_admin_id() throws Exception {
    // when & then
    mockMvc
        .perform(
            asAdmin(
                delete(
                    "/api/v1/seat/admin/{performanceId}/{seatId}/hold", PERFORMANCE_ID, SEAT_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true));

    verify(seatFacade).forceReleaseHold(ADMIN_ID, PERFORMANCE_ID, SEAT_ID);
  }

  @Test
  @DisplayName("선점 중이 아닌 좌석의 강제 해제는 409로 거절된다")
  void forceReleaseHold_returns_409_when_seat_is_not_held() throws Exception {
    // given
    willThrow(new BusinessException(ErrorStatus.SEAT_NOT_HELD))
        .given(seatFacade)
        .forceReleaseHold(ADMIN_ID, PERFORMANCE_ID, SEAT_ID);

    // when & then
    mockMvc
        .perform(
            asAdmin(
                delete(
                    "/api/v1/seat/admin/{performanceId}/{seatId}/hold", PERFORMANCE_ID, SEAT_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.SEAT_NOT_HELD.getCode()));
  }

  @Test
  @DisplayName("공연에 속하지 않는 좌석 상세 조회는 404로 거절된다")
  void getSeatDetail_returns_404_when_seat_belongs_to_other_performance() throws Exception {
    // given
    given(seatFacade.getAdminSeatDetail(PERFORMANCE_ID, SEAT_ID))
        .willThrow(new BusinessException(ErrorStatus.SEAT_NOT_FOUND));

    // when & then
    mockMvc
        .perform(
            asAdmin(get("/api/v1/seat/admin/{performanceId}/{seatId}", PERFORMANCE_ID, SEAT_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorStatus.SEAT_NOT_FOUND.getCode()));
  }

  @Test
  @DisplayName("ADMIN이 아닌 사용자는 좌석 관리자 API 세 경로 모두에 접근할 수 없다")
  void adminApis_are_forbidden_for_non_admin() throws Exception {
    // when & then
    mockMvc
        .perform(asUser(get("/api/v1/seat/admin/{performanceId}/monitoring", PERFORMANCE_ID)))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            asUser(get("/api/v1/seat/admin/{performanceId}/{seatId}", PERFORMANCE_ID, SEAT_ID)))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            asUser(
                delete(
                    "/api/v1/seat/admin/{performanceId}/{seatId}/hold", PERFORMANCE_ID, SEAT_ID)))
        .andExpect(status().isForbidden());

    verifyNoInteractions(seatFacade);
  }

  @Test
  @DisplayName("게이트웨이 인증 헤더가 없으면 401로 거절된다")
  void adminApis_are_unauthorized_without_gateway_headers() throws Exception {
    // given: seat-service는 이 이슈 전까지 anyRequest().permitAll()이라 미인증 응답 경로 자체가 없었다 (#562)
    mockMvc
        .perform(get("/api/v1/seat/admin/{performanceId}/monitoring", PERFORMANCE_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.UNAUTHORIZED.getCode()))
        // 한글 메시지가 ISO-8859-1로 파괴되지 않는지 함께 고정한다 (#560 선례)
        .andExpect(jsonPath("$.message").value(ErrorStatus.UNAUTHORIZED.getMessage()));

    verifyNoInteractions(seatFacade);
  }

  @Test
  @DisplayName("게이트웨이 토큰이 위조되면 역할 헤더가 있어도 401로 거절된다")
  void adminApis_reject_forged_role_header_without_valid_gateway_token() throws Exception {
    // given: X-User-Role만 붙여 관리자를 사칭하는 요청. GatewayHeaderFilter가 게이트웨이 토큰을 먼저 검증한다.
    mockMvc
        .perform(
            get("/api/v1/seat/admin/{performanceId}/monitoring", PERFORMANCE_ID)
                .header("X-Gateway-Token", "forged")
                .header("X-User-Id", 7L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(seatFacade);
  }
}
