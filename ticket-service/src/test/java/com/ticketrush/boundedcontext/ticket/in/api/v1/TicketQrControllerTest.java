package com.ticketrush.boundedcontext.ticket.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketQrResponse;
import com.ticketrush.boundedcontext.ticket.app.usecase.TicketQrGetUseCase;
import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.global.config.CustomSecurityProperties;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.support.WebMvcSliceTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcSliceTest(TicketQrController.class)
@Import({SecurityConfig.class, GatewayHeaderFilter.class, CustomSecurityProperties.class})
@TestPropertySource(
    properties = {
      "gateway.internal-token=test-token",
      "custom.security.internal-token=test-internal-token"
    })
class TicketQrControllerTest {

  private static final String INTERNAL_TOKEN = "test-token";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TicketQrGetUseCase ticketQrGetUseCase;

  @Test
  @DisplayName("성공: 인증 사용자가 본인 예매의 QR payload를 200으로 조회한다")
  void getTicketQr_success() throws Exception {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    TicketQrResponse response =
        new TicketQrResponse(
            "jwt-payload",
            TicketStatus.UNUSED,
            LocalDateTime.of(2026, 6, 25, 10, 0),
            LocalDateTime.of(2026, 6, 25, 10, 5));
    given(ticketQrGetUseCase.execute(userId, bookingId)).willReturn(response);

    // when & then
    mockMvc
        .perform(
            get("/api/v1/ticket/bookings/{bookingId}/qr", bookingId)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.payload").value("jwt-payload"))
        .andExpect(jsonPath("$.result.ticket_status").value("UNUSED"))
        .andExpect(jsonPath("$.result.issued_at").value("2026-06-25 10:00:00"))
        .andExpect(jsonPath("$.result.expires_at").value("2026-06-25 10:05:00"));
  }

  @Test
  @DisplayName("실패: Gateway 인증 헤더가 없으면 401을 반환한다")
  void getTicketQr_fails_when_unauthenticated() throws Exception {
    // when & then
    mockMvc
        .perform(get("/api/v1/ticket/bookings/{bookingId}/qr", 100L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.UNAUTHORIZED.getCode()));

    verifyNoInteractions(ticketQrGetUseCase);
  }

  @Test
  @DisplayName("실패: 본인 예매가 아니거나 티켓이 없으면 404 TICKET_404_001을 반환한다")
  void getTicketQr_fails_when_not_found() throws Exception {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    given(ticketQrGetUseCase.execute(userId, bookingId))
        .willThrow(new BusinessException(ErrorStatus.TICKET_NOT_FOUND));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/ticket/bookings/{bookingId}/qr", bookingId)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.TICKET_NOT_FOUND.getCode()));
  }

  @Test
  @DisplayName("실패: 미확정/취소 예매는 409 TICKET_409_001을 반환한다")
  void getTicketQr_fails_when_not_usable() throws Exception {
    // given
    Long userId = 10L;
    Long bookingId = 100L;
    given(ticketQrGetUseCase.execute(userId, bookingId))
        .willThrow(new BusinessException(ErrorStatus.TICKET_NOT_USABLE));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/ticket/bookings/{bookingId}/qr", bookingId)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.TICKET_NOT_USABLE.getCode()));
  }
}
