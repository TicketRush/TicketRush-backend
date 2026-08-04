package com.ticketrush.boundedcontext.booking.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.facade.BookingFacade;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

@WebMvcTest(BookingAdminController.class)
@Import({
  JacksonConfig.class,
  SecurityConfig.class,
  GatewayHeaderFilter.class,
  CustomSecurityProperties.class
})
@TestPropertySource(properties = "gateway.internal-token=test-token")
class BookingAdminControllerTest {

  private static final String INTERNAL_TOKEN = "test-token";
  private static final String BOOKING_NUMBER = "BOOK-1234";
  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 7, 10, 12, 0);

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BookingFacade bookingFacade;

  @Test
  @DisplayName("ADMIN이 환불 실패 예매를 조회하면 실패 시각이 함께 응답된다")
  void getRefundFailedBookings_returns_bookings_with_refund_failed_at() throws Exception {
    // given: 환불 실패는 별도 상태가 아니라 CONFIRMED + refundFailedAt으로 표현된다 (#391)
    BookingSummaryResponse response =
        new BookingSummaryResponse(
            100L,
            BOOKING_NUMBER,
            5L,
            2L,
            3L,
            BookingStatus.CONFIRMED,
            LocalDateTime.of(2026, 5, 22, 10, 30),
            FAILED_AT,
            FAILED_AT,
            null);

    given(bookingFacade.getRefundFailedBookings(new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/refund-failed")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result[0].booking_number").value(BOOKING_NUMBER))
        .andExpect(jsonPath("$.result[0].user_id").value(5))
        .andExpect(jsonPath("$.result[0].booking_status").value("CONFIRMED"))
        .andExpect(jsonPath("$.result[0].refund_failed_at").value("2026-07-10 12:00:00"))
        .andExpect(jsonPath("$.pagination_info.total_elements").value(1));

    verify(bookingFacade).getRefundFailedBookings(new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("ADMIN이 환불 고착 예매를 조회하면 REFUNDING 진입 시각(updatedAt)이 함께 응답된다")
  void getRefundingStuckBookings_returns_stuck_bookings_with_updated_at() throws Exception {
    // given: REFUNDING에서 임계 시간 이상 멈춘 고착 예매 (#397)
    LocalDateTime stuckSince = LocalDateTime.of(2026, 7, 13, 11, 0);
    BookingSummaryResponse response =
        new BookingSummaryResponse(
            100L,
            BOOKING_NUMBER,
            5L,
            2L,
            3L,
            BookingStatus.REFUNDING,
            LocalDateTime.of(2026, 5, 22, 10, 30),
            null,
            stuckSince,
            null);

    given(bookingFacade.getRefundingStuckBookings(new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/refunding-stuck")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result[0].booking_number").value(BOOKING_NUMBER))
        .andExpect(jsonPath("$.result[0].booking_status").value("REFUNDING"))
        .andExpect(jsonPath("$.result[0].updated_at").value("2026-07-13 11:00:00"))
        .andExpect(jsonPath("$.pagination_info.total_elements").value(1));

    verify(bookingFacade).getRefundingStuckBookings(new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("ADMIN이 환불 재시도를 요청하면 facade로 위임된다")
  void retryRefund_delegates_to_facade() throws Exception {
    // when & then
    mockMvc
        .perform(
            post("/api/v1/booking/admin/{bookingNumber}/refund-retry", BOOKING_NUMBER)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true));

    verify(bookingFacade).retryRefund(1L, BOOKING_NUMBER);
  }

  @Test
  @DisplayName("ADMIN이 아닌 사용자는 관리자 API에 접근할 수 없다")
  void adminApi_is_forbidden_for_non_admin() throws Exception {
    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/refund-failed")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(bookingFacade);
  }

  @Test
  @DisplayName("ADMIN이 전체 예매 목록을 조회하면 공연·예매자·좌석 보강 필드가 함께 응답된다")
  void getBookings_returns_enriched_bookings() throws Exception {
    // given
    BookingAdminSummaryResponse response =
        new BookingAdminSummaryResponse(
            100L,
            BOOKING_NUMBER,
            5L,
            2L,
            3L,
            BookingStatus.CONFIRMED,
            LocalDateTime.of(2026, 5, 22, 10, 30),
            "오페라의 유령",
            LocalDate.of(2026, 5, 22),
            "김소희",
            "user@example.com",
            "A-1",
            1,
            150000L);

    given(bookingFacade.getAdminBookings(1L, new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result[0].booking_number").value(BOOKING_NUMBER))
        .andExpect(jsonPath("$.result[0].booked_at").value("2026-05-22 10:30:00"))
        .andExpect(jsonPath("$.result[0].performance_title").value("오페라의 유령"))
        .andExpect(jsonPath("$.result[0].performance_date").value("2026-05-22"))
        .andExpect(jsonPath("$.result[0].booker_name").value("김소희"))
        .andExpect(jsonPath("$.result[0].booker_email").value("user@example.com"))
        .andExpect(jsonPath("$.result[0].seat_number").value("A-1"))
        .andExpect(jsonPath("$.result[0].seat_count").value(1))
        .andExpect(jsonPath("$.result[0].payment_amount").value(150000))
        .andExpect(jsonPath("$.pagination_info.total_elements").value(1));

    verify(bookingFacade).getAdminBookings(1L, new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("ADMIN이 요약 통계를 조회하면 카운트 3종과 총 매출이 응답된다")
  void getBookingStats_returns_summary() throws Exception {
    // given
    given(bookingFacade.getAdminBookingStats())
        .willReturn(new BookingAdminStatsResponse(1250, 980, 120, 147000000L, true, 0));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/stats")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.total_bookings").value(1250))
        .andExpect(jsonPath("$.result.completed_bookings").value(980))
        .andExpect(jsonPath("$.result.canceled_bookings").value(120))
        .andExpect(jsonPath("$.result.total_revenue").value(147000000L))
        .andExpect(jsonPath("$.result.revenue_complete").value(true));
  }

  @Test
  @DisplayName("결제 금액이 비어 있는 확정 예매가 있으면 매출이 불완전함을 함께 알린다")
  void getBookingStats_flags_incomplete_revenue() throws Exception {
    // given: 백필되지 않은 과거 확정 예매가 남아 있어 매출이 실제보다 작다 (#561)
    given(bookingFacade.getAdminBookingStats())
        .willReturn(new BookingAdminStatsResponse(1250, 980, 120, 146000000L, false, 7));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/stats")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.completed_bookings").value(980))
        .andExpect(jsonPath("$.result.total_revenue").value(146000000L))
        .andExpect(jsonPath("$.result.revenue_complete").value(false))
        .andExpect(jsonPath("$.result.missing_amount_bookings").value(7));
  }

  @Test
  @DisplayName("ADMIN이 환불 처리를 요청하면 처리자 ID와 함께 파사드로 위임된다")
  void refundBooking_delegates_to_facade_with_admin_id() throws Exception {
    // when & then
    mockMvc
        .perform(
            post("/api/v1/booking/admin/{bookingNumber}/refund", BOOKING_NUMBER)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 42L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true));

    verify(bookingFacade).refundBooking(42L, BOOKING_NUMBER);
  }

  @Test
  @DisplayName("ADMIN이 아닌 사용자는 신규 관리자 API 세 경로에도 접근할 수 없다")
  void newAdminApis_are_forbidden_for_non_admin() throws Exception {
    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            get("/api/v1/booking/admin/bookings/stats")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            post("/api/v1/booking/admin/{bookingNumber}/refund", BOOKING_NUMBER)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(bookingFacade);
  }
}
