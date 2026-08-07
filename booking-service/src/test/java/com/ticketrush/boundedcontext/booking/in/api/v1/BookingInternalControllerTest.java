package com.ticketrush.boundedcontext.booking.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingDailyRevenueRow;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingInternalResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingInternalStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPerformanceStatsRow;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetInternalStatsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetInternalUseCase;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BookingInternalController.class)
@Import({
  JacksonConfig.class,
  SecurityConfig.class,
  GatewayHeaderFilter.class,
  CustomSecurityProperties.class
})
@TestPropertySource(
    properties = {
      "gateway.internal-token=test-token",
      "custom.security.internal-token=test-internal-token"
    })
class BookingInternalControllerTest {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BookingGetInternalUseCase bookingGetInternalUseCase;

  @MockitoBean private BookingGetInternalStatsUseCase bookingGetInternalStatsUseCase;

  /**
   * 이 테스트가 고정하는 것은 값이 아니라 <b>JSON 키 이름</b>이다. performance-service의 클라이언트가 이 키로 매핑하는데, 그쪽은 앱의
   * SNAKE_CASE 설정을 타지 않는 {@code RestClient.builder()}라 키가 어긋나도 예외 없이 조용히 0/null이 된다. 소비자 쪽 테스트만으로는
   * 여기서 이름이 바뀌는 것을 잡지 못해 양쪽 테스트가 모두 초록인 채 운영만 깨진다.
   */
  @Test
  @DisplayName("성공: 예매 집계를 snake_case 키로 200 반환한다 (#563 대시보드 계약)")
  void getStats_success() throws Exception {
    // given
    given(
            bookingGetInternalStatsUseCase.execute(
                LocalDate.of(2026, 7, 9), LocalDate.of(2026, 8, 7)))
        .willReturn(
            new BookingInternalStatsResponse(
                new BookingAdminStatsResponse(1250L, 980L, 120L, 147_000_000L, false, 3L),
                List.of(new BookingPerformanceStatsRow(100L, 30L, 5_000_000L)),
                List.of(new BookingDailyRevenueRow(LocalDate.of(2026, 8, 1), 1_000_000L))));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/internal/booking/stats")
                .param("from", "2026-07-09")
                .param("to", "2026-08-07")
                .header(INTERNAL_TOKEN_HEADER, "test-internal-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.summary.completed_bookings").value(980))
        .andExpect(jsonPath("$.result.summary.total_revenue").value(147000000))
        .andExpect(jsonPath("$.result.summary.revenue_complete").value(false))
        .andExpect(jsonPath("$.result.summary.missing_amount_bookings").value(3))
        .andExpect(jsonPath("$.result.by_performance[0].performance_id").value(100))
        .andExpect(jsonPath("$.result.by_performance[0].confirmed_count").value(30))
        .andExpect(jsonPath("$.result.by_performance[0].confirmed_revenue").value(5000000))
        .andExpect(jsonPath("$.result.by_date[0].date").value("2026-08-01"))
        .andExpect(jsonPath("$.result.by_date[0].revenue").value(1000000));
  }

  @Test
  @DisplayName("실패: 내부 토큰 없이 예매 집계를 요청하면 403을 반환한다")
  void getStats_withoutToken_forbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/internal/booking/stats")
                .param("from", "2026-07-09")
                .param("to", "2026-08-07"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("성공: 올바른 내부 토큰이면 예매 소유자/상태를 200으로 반환한다")
  void getBookingInternal_success() throws Exception {
    // given
    Long bookingId = 100L;
    given(bookingGetInternalUseCase.execute(bookingId))
        .willReturn(new BookingInternalResponse(bookingId, 10L, BookingStatus.CONFIRMED));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/internal/booking/{bookingId}", bookingId)
                .header(INTERNAL_TOKEN_HEADER, "test-internal-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.booking_id").value(100))
        .andExpect(jsonPath("$.result.user_id").value(10))
        .andExpect(jsonPath("$.result.booking_status").value("CONFIRMED"));
  }

  @Test
  @DisplayName("실패: 내부 토큰이 일치하지 않으면 403 FORBIDDEN을 반환한다")
  void getBookingInternal_fails_when_token_mismatch() throws Exception {
    // when & then
    mockMvc
        .perform(
            get("/api/v1/internal/booking/{bookingId}", 100L)
                .header(INTERNAL_TOKEN_HEADER, "wrong"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(bookingGetInternalUseCase);
  }

  @Test
  @DisplayName("실패: 내부 토큰이 없으면 403 FORBIDDEN을 반환한다")
  void getBookingInternal_fails_when_token_missing() throws Exception {
    // when & then
    mockMvc
        .perform(get("/api/v1/internal/booking/{bookingId}", 100L))
        .andExpect(status().isForbidden());

    verifyNoInteractions(bookingGetInternalUseCase);
  }
}
