package com.ticketrush.global.config;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.boundedcontext.seat.in.api.v1.SeatInternalController;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.support.WebMvcSliceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcSliceTest(SeatInternalController.class)
@Import({SecurityConfig.class, CustomSecurityProperties.class, GatewayHeaderFilter.class})
@TestPropertySource(
    properties = {
      "custom.security.internal-token=test-internal-token",
      "custom.security.permit-all=false",
      "gateway.internal-token=test-token"
    })
class InternalApiTokenFilterTest {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private static final String REQUEST_BODY =
      """
      {
        "booking_number": "X7B29-KLPW1",
        "seat_id": 1
      }
      """;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SeatFacade seatFacade;

  @Test
  @DisplayName("내부 API는 내부 토큰이 없으면 403 Forbidden을 반환한다")
  void internalApi_withoutToken_forbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/internal/seat/sold")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
        .andExpect(status().isForbidden());

    verifyNoInteractions(seatFacade);
  }

  @Test
  @DisplayName("내부 API는 내부 토큰이 틀리면 403 Forbidden을 반환한다")
  void internalApi_withWrongToken_forbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/internal/seat/sold")
                .with(csrf())
                .header(INTERNAL_TOKEN_HEADER, "wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
        .andExpect(status().isForbidden());

    verifyNoInteractions(seatFacade);
  }

  @Test
  @DisplayName("내부 API는 내부 토큰이 맞으면 요청을 통과시킨다")
  void internalApi_withValidToken_success() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/internal/seat/sold")
                .with(csrf())
                .header(INTERNAL_TOKEN_HEADER, "test-internal-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true));

    verify(seatFacade).confirmSold("X7B29-KLPW1", 1L);
  }
}
