package com.ticketrush.boundedcontext.ticket.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketInternalResponse;
import com.ticketrush.boundedcontext.ticket.app.usecase.TicketGetInternalUseCase;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.support.WebMvcSliceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcSliceTest(TicketInternalController.class)
@Import({SecurityConfig.class, GatewayHeaderFilter.class, CustomSecurityProperties.class})
@TestPropertySource(
    properties = {
      "gateway.internal-token=test-token",
      "custom.security.internal-token=test-internal-token"
    })
class TicketInternalControllerTest {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
  private static final Long BOOKING_ID = 100L;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TicketGetInternalUseCase ticketGetInternalUseCase;

  @Test
  @DisplayName("성공: 올바른 내부 토큰이면 입장권 상태를 200으로 반환한다")
  void getTicketInternal_success() throws Exception {
    // given
    given(ticketGetInternalUseCase.execute(BOOKING_ID))
        .willReturn(new TicketInternalResponse(BOOKING_ID, TicketStatus.USED));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/internal/ticket/bookings/{bookingId}", BOOKING_ID)
                .header(INTERNAL_TOKEN_HEADER, "test-internal-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.booking_id").value(100))
        .andExpect(jsonPath("$.result.ticket_status").value("USED"));
  }

  @Test
  @DisplayName("실패: 내부 토큰이 일치하지 않으면 403 FORBIDDEN을 반환한다")
  void getTicketInternal_fails_when_token_mismatch() throws Exception {
    // when & then
    mockMvc
        .perform(
            get("/api/v1/internal/ticket/bookings/{bookingId}", BOOKING_ID)
                .header(INTERNAL_TOKEN_HEADER, "wrong"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(ticketGetInternalUseCase);
  }

  @Test
  @DisplayName("실패: 내부 토큰이 없으면 403 FORBIDDEN을 반환한다")
  void getTicketInternal_fails_when_token_missing() throws Exception {
    // when & then
    mockMvc
        .perform(get("/api/v1/internal/ticket/bookings/{bookingId}", BOOKING_ID))
        .andExpect(status().isForbidden());

    verifyNoInteractions(ticketGetInternalUseCase);
  }
}
