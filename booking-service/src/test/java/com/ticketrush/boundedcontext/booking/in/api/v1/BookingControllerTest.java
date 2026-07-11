package com.ticketrush.boundedcontext.booking.in.api.v1;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingCountResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPendingResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.facade.BookingFacade;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BookingController.class)
@Import({
  JacksonConfig.class,
  SecurityConfig.class,
  GatewayHeaderFilter.class,
  CustomSecurityProperties.class
})
@TestPropertySource(properties = "gateway.internal-token=test-token")
class BookingControllerTest {

  private static final String INTERNAL_TOKEN = "test-token";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BookingFacade bookingFacade;

  @Test
  @DisplayName("인증 principal의 userId로 예매 생성을 요청한다")
  void createPendingBooking_uses_authenticated_user_id() throws Exception {
    // given
    Long userId = 1L;
    Long performanceId = 2L;
    Long seatId = 3L;
    BookingPendingResponse response = new BookingPendingResponse(100L, "BOOK-1234", "PENDING");

    given(bookingFacade.createBooking(userId, performanceId, seatId)).willReturn(response);

    String requestBody =
        """
        {
          "performance_id": 2,
          "seat_id": 3
        }
        """;

    // when & then
    mockMvc
        .perform(
            post("/api/v1/booking")
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.booking_number").value("BOOK-1234"));

    verify(bookingFacade).createBooking(eq(userId), eq(performanceId), eq(seatId));
  }

  @Test
  @DisplayName("인증 principal의 userId로 내 예매 내역을 조회한다")
  void getMyBookings_uses_authenticated_user_id() throws Exception {
    // given
    Long userId = 1L;
    BookingSummaryResponse response =
        new BookingSummaryResponse(
            100L,
            "BOOK-1234",
            userId,
            2L,
            3L,
            BookingStatus.CONFIRMED,
            LocalDateTime.of(2026, 5, 22, 10, 30),
            null);

    given(
            bookingFacade.getMyBookings(
                userId, BookingStatus.CONFIRMED, new OffsetPageRequest(0, 10)))
        .willReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/me")
                .param("status", "CONFIRMED")
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result[0].booking_number").value("BOOK-1234"))
        .andExpect(jsonPath("$.result[0].performance_id").value(2))
        .andExpect(jsonPath("$.result[0].seat_id").value(3))
        .andExpect(jsonPath("$.result[0].booking_status").value("CONFIRMED"))
        .andExpect(jsonPath("$.result[0].confirmed_at").value("2026-05-22 10:30:00"))
        .andExpect(jsonPath("$.pagination_info.page_index").value(0))
        .andExpect(jsonPath("$.pagination_info.size").value(10))
        .andExpect(jsonPath("$.pagination_info.total_elements").value(1))
        .andExpect(jsonPath("$.pagination_info.total_pages").value(1));

    verify(bookingFacade)
        .getMyBookings(eq(userId), eq(BookingStatus.CONFIRMED), eq(new OffsetPageRequest(0, 10)));
  }

  @Test
  @DisplayName("인증 principal의 userId로 내 예매 수를 조회한다")
  void countMyBookings_uses_authenticated_user_id() throws Exception {
    // given
    Long userId = 1L;
    BookingCountResponse response = new BookingCountResponse(BookingStatus.CONFIRMED, 3L);
    given(bookingFacade.countMyBookings(userId, BookingStatus.CONFIRMED)).willReturn(response);

    // when & then
    mockMvc
        .perform(
            get("/api/v1/booking/me/count")
                .param("status", "CONFIRMED")
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.booking_status").value("CONFIRMED"))
        .andExpect(jsonPath("$.result.count").value(3));

    verify(bookingFacade).countMyBookings(eq(userId), eq(BookingStatus.CONFIRMED));
  }

  @Test
  @DisplayName("인증 principal의 userId로 내 예매 취소를 요청한다")
  void cancelMyBooking_uses_authenticated_user_id() throws Exception {
    // given
    Long userId = 1L;
    String bookingNumber = "BOOK-1234";

    // when & then
    mockMvc
        .perform(
            delete("/api/v1/booking/{bookingNumber}", bookingNumber)
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true));

    verify(bookingFacade).cancelMyBooking(eq(userId), eq(bookingNumber));
  }

  @Test
  @DisplayName("인증 principal이 없으면 401 Unauthorized를 반환한다")
  void createPendingBooking_fails_when_principal_missing() throws Exception {
    // given
    String requestBody =
        """
        {
          "performance_id": 2,
          "seat_id": 3
        }
        """;

    // when & then
    mockMvc
        .perform(
            post("/api/v1/booking").contentType(MediaType.APPLICATION_JSON).content(requestBody))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.UNAUTHORIZED.getCode()));

    verifyNoInteractions(bookingFacade);
  }
}
