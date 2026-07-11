package com.ticketrush.boundedcontext.booking.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.facade.BookingFacade;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import com.ticketrush.global.filter.GatewayHeaderFilter;
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
            FAILED_AT);

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
}
