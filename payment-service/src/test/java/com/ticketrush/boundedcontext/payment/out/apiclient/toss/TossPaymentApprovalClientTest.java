package com.ticketrush.boundedcontext.payment.out.apiclient.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalRequest;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalResponse;
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
    client = new TossPaymentApprovalClient(builder.build());
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
          "approvedAt": "2026-05-22T10:00:00+09:00"
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

    mockServer.verify();
  }

  @Test
  @DisplayName("Toss가 4xx를 반환하면 PAYMENT_502_001 예외가 발생한다")
  void approve_fails_when_pg_returns_4xx() {
    mockServer
        .expect(requestTo(CONFIRM_URL))
        .andRespond(
            withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"ALREADY_PROCESSED_PAYMENT\"}"));

    assertThatThrownBy(
            () ->
                client.approve(
                    new PaymentApprovalRequest(
                        PaymentProvider.TOSS, "pgKey_xyz", "BKG-0000100", 100L, 55_000L)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_APPROVAL_FAILED);

    mockServer.verify();
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
  @DisplayName("TOSS provider만 지원한다")
  void supports_toss_only() {
    assertThat(client.supports(PaymentProvider.TOSS)).isTrue();
    assertThat(client.supports(PaymentProvider.KAKAO)).isFalse();
    assertThat(client.supports(PaymentProvider.NAVER)).isFalse();
  }
}
