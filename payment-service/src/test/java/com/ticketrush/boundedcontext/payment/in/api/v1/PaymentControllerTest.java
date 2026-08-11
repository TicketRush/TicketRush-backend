package com.ticketrush.boundedcontext.payment.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentCancelRequest;
import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentCancelResponse;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentDetailResponse;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentSummaryResponse;
import com.ticketrush.boundedcontext.payment.app.facade.PaymentFacade;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.global.config.SecurityConfig;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.filter.GatewayHeaderFilter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.support.WebMvcSliceTest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
                .header("X-Gateway-Token", INTERNAL_TOKEN)
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
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 10L)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(paymentFacade);
  }

  @Test
  @DisplayName("paymentKey가 200자를 초과하면 400 Bad Request로 앞단에서 거부한다")
  void confirm_fails_when_payment_key_too_long() throws Exception {
    // given - paymentKey 201자(형식 상한 200자 초과)
    String tooLongKey = "a".repeat(201);
    String requestBody =
        """
        {
          "booking_id": 100,
          "seat_id": 200,
          "provider": "KAKAO",
          "amount": 55000,
          "payment_key": "%s"
        }
        """
            .formatted(tooLongKey);

    // when & then - VALIDATION_ERROR(@Pattern 위반)로 거부됨을 code까지 단언해 JSON 파싱 실패 등 다른 400과 구분한다.
    mockMvc
        .perform(
            post("/api/v1/payment/confirm")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 10L)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.VALIDATION_ERROR.getCode()));

    verifyNoInteractions(paymentFacade);
  }

  @Test
  @DisplayName("paymentKey에 제어문자가 있으면 400 Bad Request로 앞단에서 거부한다")
  void confirm_fails_when_payment_key_has_control_char() throws Exception {
    // given - paymentKey에 제어문자(개행)가 포함되어 인쇄가능 ASCII 화이트리스트 위반
    String requestBody =
        """
        {
          "booking_id": 100,
          "seat_id": 200,
          "provider": "KAKAO",
          "amount": 55000,
          "payment_key": "pg\\nKey_xyz"
        }
        """;

    // when & then - JSON 파싱 실패가 아니라 @Pattern(VALIDATION_ERROR) 위반으로 거부됨을 code로 특정한다.
    mockMvc
        .perform(
            post("/api/v1/payment/confirm")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 10L)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.VALIDATION_ERROR.getCode()));

    verifyNoInteractions(paymentFacade);
  }

  @Test
  @DisplayName("paymentKey가 상한 200자면 검증을 통과해 confirm으로 전달된다")
  void confirm_succeeds_when_payment_key_at_max_length() throws Exception {
    // given - 형식 상한 정확히 200자(off-by-one 회귀 방어: {1,200}이 200자를 허용해야 함)
    Long userId = 10L;
    String maxLengthKey = "a".repeat(200);
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
          "payment_key": "%s"
        }
        """
            .formatted(maxLengthKey);

    // when & then
    mockMvc
        .perform(
            post("/api/v1/payment/confirm")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk());

    verify(paymentFacade).confirm(eq(userId), any(PaymentConfirmRequest.class));
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
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.PAYMENT_AMOUNT_MISMATCH.getCode()));

    verify(paymentFacade).confirm(eq(userId), any(PaymentConfirmRequest.class));
  }

  @Test
  @DisplayName("결제 취소(환불) 요청을 성공하고 200 OK를 반환한다")
  void cancel_success() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    LocalDateTime canceledAt = LocalDateTime.of(2026, 5, 22, 10, 0);
    PaymentCancelResponse response =
        new PaymentCancelResponse(paymentId, "CANCELED", 5L, 55_000L, canceledAt);
    given(paymentFacade.cancel(eq(userId), eq(paymentId), any(PaymentCancelRequest.class)))
        .willReturn(response);

    String requestBody =
        """
        {
          "reason": "단순 변심"
        }
        """;

    // when & then
    mockMvc
        .perform(
            post("/api/v1/payment/{paymentId}/cancel", paymentId)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.payment_id").value(1))
        .andExpect(jsonPath("$.result.status").value("CANCELED"))
        .andExpect(jsonPath("$.result.refund_id").value(5))
        .andExpect(jsonPath("$.result.refunded_amount").value(55000))
        .andExpect(jsonPath("$.result.canceled_at").value("2026-05-22 10:00:00"));

    verify(paymentFacade).cancel(eq(userId), eq(paymentId), any(PaymentCancelRequest.class));
  }

  @Test
  @DisplayName("환불 사유가 비어있으면 400 Bad Request를 반환한다")
  void cancel_fails_when_reason_missing() throws Exception {
    // given - reason 누락
    String requestBody = "{}";

    // when & then
    mockMvc
        .perform(
            post("/api/v1/payment/{paymentId}/cancel", 1L)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 10L)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(paymentFacade);
  }

  @Test
  @DisplayName("본인 결제가 아니면 PAYMENT_404_002로 실패한다")
  void cancel_fails_when_not_found() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 999L;
    given(paymentFacade.cancel(eq(userId), eq(paymentId), any(PaymentCancelRequest.class)))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_NOT_FOUND));

    String requestBody =
        """
        {
          "reason": "단순 변심"
        }
        """;

    // when & then
    mockMvc
        .perform(
            post("/api/v1/payment/{paymentId}/cancel", paymentId)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.PAYMENT_NOT_FOUND.getCode()));
  }

  @Test
  @DisplayName("결제 내역 목록 조회 성공 시 200 OK와 PageInfo를 반환한다")
  void getPayments_success() throws Exception {
    // given
    Long userId = 10L;
    PaymentSummaryResponse item =
        new PaymentSummaryResponse(
            1L,
            100L,
            PaymentProvider.TOSS,
            55_000L,
            PaymentStatus.COMPLETED,
            LocalDateTime.of(2026, 5, 1, 10, 0));
    Page<PaymentSummaryResponse> page = new PageImpl<>(List.of(item), Pageable.ofSize(10), 1);
    given(paymentFacade.getPayments(eq(userId), any(OffsetPageRequest.class))).willReturn(page);

    // when & then
    mockMvc
        .perform(
            get("/api/v1/payment")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.pagination_info.total_elements").value(1))
        .andExpect(jsonPath("$.result[0].payment_id").value(1))
        .andExpect(jsonPath("$.result[0].booking_id").value(100))
        .andExpect(jsonPath("$.result[0].status").value("COMPLETED"));

    verify(paymentFacade).getPayments(userId, new OffsetPageRequest(0, 10));
  }

  /*
   * sort는 더 이상 바인딩 대상이 아니므로 어떤 값이 오든 하위 계층에 전달되지 않는다(#475).
   * 클라이언트가 준 프로퍼티가 쿼리 실행까지 도달하는 경로 자체가 사라졌으므로,
   * "잘못된 프로퍼티가 PropertyReferenceException으로 500을 낸다"는 회귀는 검증 대상이 아니라 소멸한 것이다.
   */
  @Test
  @DisplayName("sort 파라미터는 바인딩되지 않아 기본 페이지 요청만 전달된다(#475)")
  void getPayments_ignoresSortParameter() throws Exception {
    // given
    Long userId = 10L;
    given(paymentFacade.getPayments(eq(userId), any(OffsetPageRequest.class)))
        .willReturn(Page.empty());

    // when & then
    mockMvc
        .perform(
            get("/api/v1/payment")
                .param("sort", "amount,asc")
                .param("sort", "notAnEntityProperty,desc")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true));

    verify(paymentFacade).getPayments(userId, new OffsetPageRequest(0, 10));
  }

  @Test
  @DisplayName("size가 정수로 변환되지 않으면 보정하지 않고 400으로 거부한다")
  void getPayments_rejectsNonNumericSize() throws Exception {
    // when & then
    mockMvc
        .perform(
            get("/api/v1/payment")
                .param("size", "abc")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", 10L)
                .header("X-User-Role", "USER"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.VALIDATION_ERROR.getCode()));

    verifyNoInteractions(paymentFacade);
  }

  @Test
  @DisplayName("size가 상한을 넘으면 50으로 보정되어 전달된다")
  void getPayments_clampsPageSize() throws Exception {
    // given
    Long userId = 10L;
    given(paymentFacade.getPayments(eq(userId), any(OffsetPageRequest.class)))
        .willReturn(Page.empty());
    ArgumentCaptor<OffsetPageRequest> captor = ArgumentCaptor.forClass(OffsetPageRequest.class);

    // when & then
    mockMvc
        .perform(
            get("/api/v1/payment")
                .param("page", "1")
                .param("size", "2000")
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isOk());

    verify(paymentFacade).getPayments(eq(userId), captor.capture());
    assertThat(captor.getValue().size()).isEqualTo(50);
    assertThat(captor.getValue().page()).isEqualTo(1);
  }

  @Test
  @DisplayName("결제 내역 단건 조회 성공 시 200 OK와 상세 정보를 반환한다")
  void getPayment_success() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 1L;
    PaymentDetailResponse response =
        new PaymentDetailResponse(
            paymentId,
            100L,
            PaymentProvider.TOSS,
            55_000L,
            PaymentStatus.COMPLETED,
            LocalDateTime.of(2026, 5, 1, 10, 0),
            "APR-001",
            null);
    given(paymentFacade.getPayment(userId, paymentId)).willReturn(response);

    // when & then
    mockMvc
        .perform(
            get("/api/v1/payment/{paymentId}", paymentId)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.is_success").value(true))
        .andExpect(jsonPath("$.result.payment_id").value(1))
        .andExpect(jsonPath("$.result.approval_number").value("APR-001"))
        .andExpect(jsonPath("$.result.payment_key").doesNotExist())
        .andExpect(jsonPath("$.result.refund").doesNotExist());
  }

  @Test
  @DisplayName("결제 내역 단건 조회 시 본인 결제가 아니면 PAYMENT_404_002로 실패한다")
  void getPayment_fails_when_not_found() throws Exception {
    // given
    Long userId = 10L;
    Long paymentId = 999L;
    given(paymentFacade.getPayment(userId, paymentId))
        .willThrow(new BusinessException(ErrorStatus.PAYMENT_NOT_FOUND));

    // when & then
    mockMvc
        .perform(
            get("/api/v1/payment/{paymentId}", paymentId)
                .header("X-Gateway-Token", INTERNAL_TOKEN)
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.is_success").value(false))
        .andExpect(jsonPath("$.code").value(ErrorStatus.PAYMENT_NOT_FOUND.getCode()));
  }
}
