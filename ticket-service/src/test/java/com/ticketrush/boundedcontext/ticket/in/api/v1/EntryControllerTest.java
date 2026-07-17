package com.ticketrush.boundedcontext.ticket.in.api.v1;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.ticket.app.dto.response.EntryCheckInResponse;
import com.ticketrush.boundedcontext.ticket.app.dto.response.EntryVerifyResponse;
import com.ticketrush.boundedcontext.ticket.app.usecase.EntryCheckInUseCase;
import com.ticketrush.boundedcontext.ticket.app.usecase.EntryVerifyUseCase;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcSliceTest(EntryController.class)
@Import({SecurityConfig.class, GatewayHeaderFilter.class, CustomSecurityProperties.class})
@TestPropertySource(
    properties = {
      "gateway.internal-token=test-token",
      "custom.security.internal-token=test-internal-token"
    })
class EntryControllerTest {

  private static final String INTERNAL_TOKEN = "test-token";
  private static final String BODY = "{\"token\":\"qr-token\"}";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private EntryVerifyUseCase entryVerifyUseCase;

  @MockitoBean private EntryCheckInUseCase entryCheckInUseCase;

  @Test
  @DisplayName("성공: ADMIN이 QR을 검증하면 200과 입장 가능 정보를 반환한다")
  void verify_success_for_admin() throws Exception {
    // given
    given(entryVerifyUseCase.execute("qr-token"))
        .willReturn(new EntryVerifyResponse(1L, 100L, TicketStatus.UNUSED));

    // when & then
    mockMvc
        .perform(
            post("/api/v1/entries/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.ticket_id").value(1))
        .andExpect(jsonPath("$.result.booking_id").value(100))
        .andExpect(jsonPath("$.result.ticket_status").value("UNUSED"));
  }

  @Test
  @DisplayName("성공: ADMIN이 입장 처리하면 200과 USED/usedAt을 반환한다")
  void checkIn_success_for_admin() throws Exception {
    // given
    given(entryCheckInUseCase.execute("qr-token"))
        .willReturn(EntryCheckInResponse.of(1L, LocalDateTime.of(2026, 6, 26, 19, 30, 0)));

    // when & then
    mockMvc
        .perform(
            post("/api/v1/entries/check-in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.ticket_id").value(1))
        .andExpect(jsonPath("$.result.ticket_status").value("USED"))
        .andExpect(jsonPath("$.result.used_at").value("2026-06-26 19:30:00"));
  }

  @Test
  @DisplayName("실패: Gateway 인증 헤더가 없으면 401을 반환하고 UseCase를 호출하지 않는다")
  void verify_fails_when_unauthenticated() throws Exception {
    // when & then
    mockMvc
        .perform(
            post("/api/v1/entries/verify").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.UNAUTHORIZED.getCode()));

    verifyNoInteractions(entryVerifyUseCase, entryCheckInUseCase);
  }

  @Test
  @DisplayName("실패: ADMIN이 아닌 사용자는 403을 반환하고 UseCase를 호출하지 않는다")
  void verify_fails_when_not_admin() throws Exception {
    // when & then
    mockMvc
        .perform(
            post("/api/v1/entries/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "USER"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.FORBIDDEN.getCode()));

    verifyNoInteractions(entryVerifyUseCase, entryCheckInUseCase);
  }

  @Test
  @DisplayName("실패: 이미 사용된 입장권은 409 TICKET_409_002를 반환한다")
  void checkIn_fails_when_already_used() throws Exception {
    // given
    given(entryCheckInUseCase.execute("qr-token"))
        .willThrow(new BusinessException(ErrorStatus.TICKET_ALREADY_USED));

    // when & then
    mockMvc
        .perform(
            post("/api/v1/entries/check-in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.TICKET_ALREADY_USED.getCode()));
  }

  @Test
  @DisplayName("실패: 만료된 QR은 401 TICKET_401_001을 반환한다")
  void verify_fails_when_qr_expired() throws Exception {
    // given
    given(entryVerifyUseCase.execute("qr-token"))
        .willThrow(new BusinessException(ErrorStatus.TICKET_QR_EXPIRED));

    // when & then
    mockMvc
        .perform(
            post("/api/v1/entries/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.TICKET_QR_EXPIRED.getCode()));
  }

  @Test
  @DisplayName("실패: 서명/형식이 무효한 QR은 400 TICKET_400_001을 반환한다")
  void verify_fails_when_qr_invalid() throws Exception {
    // given
    given(entryVerifyUseCase.execute("qr-token"))
        .willThrow(new BusinessException(ErrorStatus.TICKET_QR_INVALID));

    // when & then
    mockMvc
        .perform(
            post("/api/v1/entries/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.TICKET_QR_INVALID.getCode()));
  }

  @Test
  @DisplayName("실패: token이 비어 있으면 400 검증 오류를 반환한다")
  void verify_fails_when_token_blank() throws Exception {
    // when & then
    mockMvc
        .perform(
            post("/api/v1/entries/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"\"}")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 1L)
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.is_success").value(false));

    verifyNoInteractions(entryVerifyUseCase, entryCheckInUseCase);
  }
}
