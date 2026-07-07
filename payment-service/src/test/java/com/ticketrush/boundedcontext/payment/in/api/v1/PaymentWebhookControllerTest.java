package com.ticketrush.boundedcontext.payment.in.api.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.payment.app.facade.PaymentFacade;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.support.WebMvcSliceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcSliceTest(PaymentWebhookController.class)
@Import({SecurityConfig.class, GatewayHeaderFilter.class})
@TestPropertySource(properties = "gateway.internal-token=test-token")
class PaymentWebhookControllerTest {

  private static final String BODY =
      """
      {
        "eventType": "PAYMENT_STATUS_CHANGED",
        "data": { "paymentKey": "tossKey_abc", "orderId": "BKG-0000100", "status": "DONE" }
      }
      """;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PaymentFacade paymentFacade;

  @Test
  @DisplayName("게이트웨이 헤더 없이도 Webhook을 수신하고 200 OK를 반환한다")
  void webhook_success_without_gateway_headers() throws Exception {
    // given
    willDoNothing().given(paymentFacade).handleWebhook(any(byte[].class));

    // when & then
    mockMvc
        .perform(
            post("/api/v1/payment/webhook").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk());

    verify(paymentFacade).handleWebhook(any(byte[].class));
  }

  @Test
  @DisplayName("재조회 검증 실패(위조) 시 PAYMENT_401_001로 거부한다")
  void webhook_fails_when_invalid() throws Exception {
    // given
    willThrow(new BusinessException(ErrorStatus.PAYMENT_WEBHOOK_INVALID))
        .given(paymentFacade)
        .handleWebhook(any(byte[].class));

    // when & then
    mockMvc
        .perform(
            post("/api/v1/payment/webhook").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.PAYMENT_WEBHOOK_INVALID.getCode()));
  }

  @Test
  @DisplayName("페이로드 파싱 실패 시 PAYMENT_400_006으로 거부한다")
  void webhook_fails_when_payload_invalid() throws Exception {
    // given
    willThrow(new BusinessException(ErrorStatus.PAYMENT_WEBHOOK_PAYLOAD_INVALID))
        .given(paymentFacade)
        .handleWebhook(any(byte[].class));

    // when & then
    mockMvc
        .perform(
            post("/api/v1/payment/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ malformed"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.PAYMENT_WEBHOOK_PAYLOAD_INVALID.getCode()));
  }
}
