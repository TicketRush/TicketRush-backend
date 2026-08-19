package com.ticketrush.boundedcontext.payment.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TossPaymentApprovalClientTest {

  private static final String BASE_URL = "https://api.tosspayments.com";
  private static final String CONFIRM_URL = BASE_URL + "/v1/payments/confirm";

  private MockRestServiceServer mockServer;
  private TossPaymentApprovalClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();
    client = new TossPaymentApprovalClient(builder.build(), new ObjectMapper());
  }

  @Test
  @DisplayName("Toss 결제 승인 성공 시 transactionKey를 approvalNumber로 매핑한다")
  void approve_success() {
    String responseBody =
        """
        {
          "paymentKey": "pgKey_xyz",
          "orderId": "BKG-0000100",
          "transactionKey": "TX-ABC123",
          "totalAmount": 55000,
          "status": "DONE",
          "approvedAt": "2026-05-22T10:00:00+09:00",
          "method": "카드"
        }
        """;

    mockServer
        .expect(requestTo(CONFIRM_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.paymentKey").value("pgKey_xyz"))
        .andExpect(jsonPath("$.orderId").value("BKG-0000100"))
        .andExpect(jsonPath("$.amount").value(55000))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    PaymentApprovalResponse response =
        client.approve(
            new PaymentApprovalRequest(
                PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000100", 100L, 55_000L));

    assertThat(response.approvalNumber()).isEqualTo("TX-ABC123");
    assertThat(response.approvedAmount()).isEqualTo(55_000L);
    assertThat(response.approvedAt()).isNotNull();
    assertThat(response.method()).isEqualTo("카드");

    mockServer.verify();
  }

  @Test
  @DisplayName("승인 응답에 method가 없어도 승인은 성공하고 결제수단만 null이 된다")
  void approve_succeeds_when_method_is_missing() {
    /* approvedAt·approvalNumber 누락은 PAYMENT_PG_COMMUNICATION_FAILED로 승인을 실패시키지만(아래
     * approve_fails_when_approved_at_is_missing 참고), method는 Toss 명세상 nullable이라 같은 취급을 하면 안 된다.
     * 결제수단은 보존 대상일 뿐 승인 성립 요건이 아니다(#593). 이 테스트가 그 경계를 고정한다. */
    String responseBody =
        """
        {
          "paymentKey": "pgKey_xyz",
          "orderId": "BKG-0000100",
          "transactionKey": "TX-ABC123",
          "totalAmount": 55000,
          "status": "DONE",
          "approvedAt": "2026-05-22T10:00:00+09:00"
        }
        """;

    mockServer
        .expect(requestTo(CONFIRM_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    PaymentApprovalResponse response =
        client.approve(
            new PaymentApprovalRequest(
                PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000100", 100L, 55_000L));

    assertThat(response.method()).isNull();
    assertThat(response.approvalNumber()).isEqualTo("TX-ABC123");
    assertThat(response.approvedAmount()).isEqualTo(55_000L);

    mockServer.verify();
  }

  @Test
  @DisplayName("Toss 4xx + ALREADY_PROCESSED_PAYMENT → PAYMENT_ALREADY_COMPLETED")
  void approve_maps_already_processed_payment() {
    expect4xxWithCode("ALREADY_PROCESSED_PAYMENT");

    assertApproveThrows(ErrorStatus.PAYMENT_ALREADY_COMPLETED);

    mockServer.verify();
  }

  @Test
  @DisplayName("Toss 4xx + NOT_FOUND_PAYMENT_SESSION → PAYMENT_SESSION_NOT_FOUND")
  void approve_maps_not_found_payment_session() {
    expect4xxWithCode("NOT_FOUND_PAYMENT_SESSION");

    assertApproveThrows(ErrorStatus.PAYMENT_SESSION_NOT_FOUND);

    mockServer.verify();
  }

  @Test
  @DisplayName("Toss 4xx + INVALID_CARD_NUMBER → PAYMENT_METHOD_REJECTED (prefix 매칭)")
  void approve_maps_invalid_card_prefix() {
    expect4xxWithCode("INVALID_CARD_NUMBER");

    assertApproveThrows(ErrorStatus.PAYMENT_METHOD_REJECTED);

    mockServer.verify();
  }

  @Test
  @DisplayName("Toss 4xx + EXCEED_MAX_DAILY_PAYMENT_COUNT → PAYMENT_LIMIT_EXCEEDED (prefix 매칭)")
  void approve_maps_exceed_max_daily_payment_prefix() {
    expect4xxWithCode("EXCEED_MAX_DAILY_PAYMENT_COUNT");

    assertApproveThrows(ErrorStatus.PAYMENT_LIMIT_EXCEEDED);

    mockServer.verify();
  }

  @Test
  @DisplayName("Toss 4xx + EXCEED_MAX_PAYMENT_AMOUNT → PAYMENT_LIMIT_EXCEEDED")
  void approve_maps_exceed_max_payment_amount() {
    expect4xxWithCode("EXCEED_MAX_PAYMENT_AMOUNT");

    assertApproveThrows(ErrorStatus.PAYMENT_LIMIT_EXCEEDED);

    mockServer.verify();
  }

  @Test
  @DisplayName("Toss 4xx + EXCEED_MAX_ONE_DAY_AMOUNT → PAYMENT_LIMIT_EXCEEDED")
  void approve_maps_exceed_max_one_day_amount() {
    expect4xxWithCode("EXCEED_MAX_ONE_DAY_AMOUNT");

    assertApproveThrows(ErrorStatus.PAYMENT_LIMIT_EXCEEDED);

    mockServer.verify();
  }

  @Test
  @DisplayName("Toss 4xx + EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT → PAYMENT_LIMIT_EXCEEDED")
  void approve_maps_exceed_max_monthly_payment_amount() {
    expect4xxWithCode("EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT");

    assertApproveThrows(ErrorStatus.PAYMENT_LIMIT_EXCEEDED);

    mockServer.verify();
  }

  @Test
  @DisplayName("Toss 4xx + FORBIDDEN_REQUEST → PAYMENT_PG_AUTH_FAILED")
  void approve_maps_forbidden_request() {
    expect4xxWithCode("FORBIDDEN_REQUEST");

    assertApproveThrows(ErrorStatus.PAYMENT_PG_AUTH_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("Toss 4xx + 미정의 코드 → PAYMENT_APPROVAL_FAILED 폴백")
  void approve_falls_back_for_unknown_code() {
    expect4xxWithCode("SOME_UNDEFINED_TOSS_CODE");

    assertApproveThrows(ErrorStatus.PAYMENT_APPROVAL_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("Toss 4xx 거절 시 원본 code/message를 PgRejectionException에 실어 던진다")
  void approve_carries_raw_toss_code_and_message() {
    expect4xxWithCode("REJECT_CARD_COMPANY");

    assertThatThrownBy(
            () ->
                client.approve(
                    new PaymentApprovalRequest(
                        PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000100", 100L, 55_000L)))
        .isInstanceOf(PgRejectionException.class)
        .extracting("errorStatus", "rawCode", "rawMessage")
        .containsExactly(ErrorStatus.PAYMENT_METHOD_REJECTED, "REJECT_CARD_COMPANY", "테스트 거절 메시지");

    mockServer.verify();
  }

  @Test
  @DisplayName("미정의 코드도 매핑 이전 원본 code를 그대로 보존한다")
  void approve_preserves_raw_code_for_unknown() {
    expect4xxWithCode("SOME_UNDEFINED_TOSS_CODE");

    assertThatThrownBy(
            () ->
                client.approve(
                    new PaymentApprovalRequest(
                        PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000100", 100L, 55_000L)))
        .isInstanceOf(PgRejectionException.class)
        .extracting("rawCode")
        .isEqualTo("SOME_UNDEFINED_TOSS_CODE");

    mockServer.verify();
  }

  private void expect4xxWithCode(String tossCode) {
    mockServer
        .expect(requestTo(CONFIRM_URL))
        .andRespond(
            withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"" + tossCode + "\",\"message\":\"테스트 거절 메시지\"}"));
  }

  private void assertApproveThrows(ErrorStatus expected) {
    assertThatThrownBy(
            () ->
                client.approve(
                    new PaymentApprovalRequest(
                        PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000100", 100L, 55_000L)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("Toss가 5xx를 반환하면 PAYMENT_503_001 예외가 발생한다")
  void approve_fails_when_pg_returns_5xx() {
    mockServer.expect(requestTo(CONFIRM_URL)).andRespond(withServerError());

    assertThatThrownBy(
            () ->
                client.approve(
                    new PaymentApprovalRequest(
                        PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000100", 100L, 55_000L)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("응답 본문이 비어있어도 통신 실패로 처리한다")
  void approve_fails_when_response_body_is_empty() {
    mockServer
        .expect(requestTo(CONFIRM_URL))
        .andRespond(withRawStatus(200).contentType(MediaType.APPLICATION_JSON).body(""));

    assertThatThrownBy(
            () ->
                client.approve(
                    new PaymentApprovalRequest(
                        PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000100", 100L, 55_000L)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("응답에 approvedAt이 없으면 통신 실패로 처리한다")
  void approve_fails_when_approved_at_is_missing() {
    String responseBody =
        """
        {
          "paymentKey": "pgKey_xyz",
          "orderId": "BKG-0000100",
          "transactionKey": "TX-ABC123",
          "totalAmount": 55000,
          "status": "DONE"
        }
        """;

    mockServer
        .expect(requestTo(CONFIRM_URL))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    assertThatThrownBy(
            () ->
                client.approve(
                    new PaymentApprovalRequest(
                        PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000100", 100L, 55_000L)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("transactionKey가 없으면 paymentKey로 폴백하고 클라이언트는 길이를 자르지 않는다")
  void approve_falls_back_to_payment_key_without_truncating() {
    /* 폴백 값은 계약 상한인 200자까지 올 수 있다(#413). 자르는 책임은 엔티티(Payment 생성자)에 있고 클라이언트는 PG
     * 원본을 그대로 전달한다 — #619가 그 경계를 이 테스트로 고정한다. 길이 초과는 관측용 warn 로그로만 남는다. */
    String longPaymentKey = "P".repeat(200);
    String responseBody =
        """
        {
          "paymentKey": "%s",
          "orderId": "BKG-0000100",
          "totalAmount": 55000,
          "status": "DONE",
          "approvedAt": "2026-05-22T10:00:00+09:00"
        }
        """
            .formatted(longPaymentKey);

    mockServer
        .expect(requestTo(CONFIRM_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    PaymentApprovalResponse response =
        client.approve(
            new PaymentApprovalRequest(
                PaymentProvider.TOSS, longPaymentKey, "BKG-0000100", 100L, 55_000L));

    assertThat(response.approvalNumber()).isEqualTo(longPaymentKey);
    assertThat(response.approvalNumber()).hasSize(200);

    mockServer.verify();
  }

  @Test
  @DisplayName("transactionKey가 빈 문자열이면 누락과 동일하게 paymentKey로 폴백한다")
  void approve_falls_back_when_transaction_key_is_blank() {
    /* 폴백 판정이 != null이면 빈 문자열이 그대로 승인번호가 되어 빈 값이 조용히 저장된다. 취소 경로
     * (TossPaymentCancelClient)가 이미 hasText로 판정하고 있어 같은 기준으로 맞췄다(#619). */
    String responseBody =
        """
        {
          "paymentKey": "pgKey_xyz",
          "orderId": "BKG-0000100",
          "transactionKey": "",
          "totalAmount": 55000,
          "status": "DONE",
          "approvedAt": "2026-05-22T10:00:00+09:00"
        }
        """;

    mockServer
        .expect(requestTo(CONFIRM_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    PaymentApprovalResponse response =
        client.approve(
            new PaymentApprovalRequest(
                PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000100", 100L, 55_000L));

    assertThat(response.approvalNumber()).isEqualTo("pgKey_xyz");

    mockServer.verify();
  }

  @Test
  @DisplayName("transactionKey와 paymentKey가 모두 빈 문자열이어도 승인을 실패시키지는 않는다")
  void approve_does_not_fail_when_both_identifiers_are_blank() {
    /* blank 판정은 폴백을 고를 때만 쓰고, 최종 가드는 null만 막는다. 이 가드가 서 있는 지점은 PG 승인(과금)이 이미 끝난
     * 뒤라서, 조회에도 정합성에도 쓰이지 않는 표시용 필드가 비었다는 이유로 여기서 실패시키면 과금은 됐는데 payment row는
     * 없는 상태가 된다. #607 자동 환불은 payment(COMPLETED) 존재를 전제하므로 그 상태에는 복구 경로도 없다 — 이번
     * 이슈가 막으려는 실패와 정확히 같은 등급이라, 빈 값으로라도 저장되는 편이 낫다(#619). */
    String responseBody =
        """
        {
          "paymentKey": "",
          "orderId": "BKG-0000100",
          "transactionKey": "",
          "totalAmount": 55000,
          "status": "DONE",
          "approvedAt": "2026-05-22T10:00:00+09:00"
        }
        """;

    mockServer
        .expect(requestTo(CONFIRM_URL))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    PaymentApprovalResponse response =
        client.approve(
            new PaymentApprovalRequest(
                PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000100", 100L, 55_000L));

    assertThat(response.approvalNumber()).isEmpty();
    assertThat(response.approvedAmount()).isEqualTo(55_000L);

    mockServer.verify();
  }

  @Test
  @DisplayName("transactionKey가 빈 문자열이고 paymentKey가 없어도 승인을 실패시키지는 않는다")
  void approve_does_not_fail_when_transaction_key_is_blank_and_payment_key_is_missing() {
    /* 폴백 판정을 hasText로 바꾸면서 한때 이 조합만 throw로 넘어갔었다(#619 리뷰). blank는 "쓸 만하지 않다"는 뜻이지
     * "아무것도 없다"는 뜻이 아니므로, 저장할 값이 하나라도 있으면 과금 이후에 실패시키지 않는다. throw는 두 값이 모두
     * null일 때로만 좁혀 둔다 — 그 계약을 이 테스트가 고정한다. */
    String responseBody =
        """
        {
          "orderId": "BKG-0000100",
          "transactionKey": "",
          "totalAmount": 55000,
          "status": "DONE",
          "approvedAt": "2026-05-22T10:00:00+09:00"
        }
        """;

    mockServer
        .expect(requestTo(CONFIRM_URL))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    PaymentApprovalResponse response =
        client.approve(
            new PaymentApprovalRequest(
                PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000100", 100L, 55_000L));

    assertThat(response.approvalNumber()).isEmpty();

    mockServer.verify();
  }

  @Test
  @DisplayName("응답에 transactionKey와 paymentKey가 모두 없으면 통신 실패로 처리한다")
  void approve_fails_when_both_identifiers_are_missing() {
    String responseBody =
        """
        {
          "orderId": "BKG-0000100",
          "totalAmount": 55000,
          "status": "DONE",
          "approvedAt": "2026-05-22T10:00:00+09:00"
        }
        """;

    mockServer
        .expect(requestTo(CONFIRM_URL))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    assertThatThrownBy(
            () ->
                client.approve(
                    new PaymentApprovalRequest(
                        PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000100", 100L, 55_000L)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("TOSS provider를 담당한다")
  void provider_is_toss() {
    assertThat(client.provider()).isEqualTo(PaymentProvider.TOSS);
    assertThat(client.isFallback()).isFalse();
  }
}
