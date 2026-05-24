package com.ticketrush.boundedcontext.payment.in.api.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.facade.PaymentFacade;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.security.GatewayHeaderFilter;
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

@WebMvcSliceTest(PaymentController.class)
@Import({SecurityConfig.class, GatewayHeaderFilter.class})
@TestPropertySource(properties = "gateway.internal-token=test-token")
class PaymentControllerTest {

  private static final String INTERNAL_TOKEN = "test-token";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PaymentFacade paymentFacade;

  @Test
  @DisplayName("결제 Confirm 요청을 성공하고 200 OK를 반환한다")
  void confirm_success() throws Exception {
    // given
    Long userId = 10L;
    LocalDateTime paidAt = LocalDateTime.of(2026, 5, 22, 10, 0);
    PaymentConfirmResponse response = new PaymentConfirmResponse(1L, "COMPLETED", paidAt);
    given(paymentFacade.confirm(eq(userId), any(PaymentConfirmRequest.class))).willReturn(response);

    String requestBody =
        """
        {
          "booking_id": 100,
          "seat_id": 200,
          "provider": "KAKAO",
          "amount": 55000,
          "payment_key": "pgKey_xyz"
        }
        """;

    // when & then
    mockMvc
        .perform(
            post("/api/v1/payment/confirm")
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.payment_id").value(1))
        .andExpect(jsonPath("$.result.status").value("COMPLETED"))
        .andExpect(jsonPath("$.result.paid_at").value("2026-05-22 10:00:00"));

    verify(paymentFacade).confirm(eq(userId), any(PaymentConfirmRequest.class));
  }

  @Test
  @DisplayName("Gateway 인증 헤더가 없으면 401 Unauthorized를 반환한다")
  void confirm_fails_when_gateway_headers_missing() throws Exception {
    // given
    String requestBody =
        """
        {
          "booking_id": 100,
          "seat_id": 200,
          "provider": "KAKAO",
          "amount": 55000,
          "payment_key": "pgKey_xyz"
        }
        """;

    // when & then
    mockMvc
        .perform(
            post("/api/v1/payment/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.UNAUTHORIZED.getCode()));

    verifyNoInteractions(paymentFacade);
  }

  @Test
  @DisplayName("필수 필드가 누락되면 400 Bad Request를 반환한다")
  void confirm_fails_when_required_field_missing() throws Exception {
    // given - bookingId 누락
    String requestBody =
        """
        {
          "seat_id": 200,
          "provider": "KAKAO",
          "amount": 55000,
          "payment_key": "pgKey_xyz"
        }
        """;

    // when & then
    mockMvc
        .perform(
            post("/api/v1/payment/confirm")
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 10L)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(paymentFacade);
  }

  @Test
  @DisplayName("결제 금액 불일치 시 PAYMENT_400_001로 실패한다")
  void confirm_fails_when_amount_mismatch() throws Exception {
    // given
    Long userId = 10L;
    given(paymentFacade.confirm(eq(userId), any(PaymentConfirmRequest.class)))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_AMOUNT_MISMATCH));

    String requestBody =
        """
        {
          "booking_id": 100,
          "seat_id": 200,
          "provider": "KAKAO",
          "amount": 55000,
          "payment_key": "pgKey_xyz"
        }
        """;

    // when & then
    mockMvc
        .perform(
            post("/api/v1/payment/confirm")
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.PAYMENT_AMOUNT_MISMATCH.getCode()));

    verify(paymentFacade).confirm(eq(userId), any(PaymentConfirmRequest.class));
  }
}
