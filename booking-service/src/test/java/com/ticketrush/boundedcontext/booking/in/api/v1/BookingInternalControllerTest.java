package com.ticketrush.boundedcontext.booking.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingInternalResponse;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetInternalUseCase;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.filter.GatewayHeaderFilter;
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
