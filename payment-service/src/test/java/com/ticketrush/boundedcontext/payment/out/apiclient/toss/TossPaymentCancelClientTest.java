package com.ticketrush.boundedcontext.payment.out.apiclient.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelCommand;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelResult;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TossPaymentCancelClientTest {

  private static final String BASE_URL = "https://api.tosspayments.com";
  private static final String CANCEL_URL = BASE_URL + "/v1/payments/pgKey_xyz/cancel";

  private MockRestServiceServer mockServer;
  private TossPaymentCancelClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();
    client = new TossPaymentCancelClient(builder.build());
  }

  @Test
  @DisplayName("Toss 결제 취소 성공 시 최신 cancel 항목을 매핑하고 멱등 키 헤더를 전송한다")
  void cancel_success() {
    String responseBody =
        """
        {
          "paymentKey": "pgKey_xyz",
          "status": "CANCELED",
          "cancels": [
            {
              "transactionKey": "TX-CANCEL-1",
              "cancelAmount": 55000,
              "canceledAt": "2026-05-22T10:00:00+09:00"
            }
          ]
        }
        """;

    mockServer
        .expect(requestTo(CANCEL_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", "REFUND-0000001"))
        .andExpect(jsonPath("$.cancelReason").value("단순 변심"))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    PaymentCancelResult result = client.cancel(command());

    assertThat(result.pgRefundKey()).isEqualTo("TX-CANCEL-1");
    assertThat(result.refundedAmount()).isEqualTo(55_000L);
    assertThat(result.canceledAt()).isNotNull();

    mockServer.verify();
  }

  @Test
  @DisplayName("transactionKey가 없으면 paymentKey로 폴백한다")
  void cancel_falls_back_to_payment_key() {
    String responseBody =
        """
        {
          "paymentKey": "pgKey_xyz",
          "status": "CANCELED",
          "cancels": [
            {
              "cancelAmount": 55000,
              "canceledAt": "2026-05-22T10:00:00+09:00"
            }
          ]
        }
        """;

    mockServer
        .expect(requestTo(CANCEL_URL))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    PaymentCancelResult result = client.cancel(command());

    assertThat(result.pgRefundKey()).isEqualTo("pgKey_xyz");

    mockServer.verify();
  }

  @Test
  @DisplayName("Toss가 4xx를 반환하면 PAYMENT_502_003 예외가 발생한다")
  void cancel_maps_4xx_to_refund_failed() {
    mockServer
        .expect(requestTo(CANCEL_URL))
        .andRespond(
            withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"ALREADY_CANCELED_PAYMENT\",\"message\":\"이미 취소됨\"}"));

    assertThatThrownBy(() -> client.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("Toss가 5xx를 반환하면 PAYMENT_503_001 예외가 발생한다")
  void cancel_maps_5xx_to_communication_failed() {
    mockServer.expect(requestTo(CANCEL_URL)).andRespond(withServerError());

    assertThatThrownBy(() -> client.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("응답 본문이 비어있어도 통신 실패로 처리한다")
  void cancel_fails_when_response_body_is_empty() {
    mockServer
        .expect(requestTo(CANCEL_URL))
        .andRespond(withRawStatus(200).contentType(MediaType.APPLICATION_JSON).body(""));

    assertThatThrownBy(() -> client.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("응답 cancels가 비어있으면 통신 실패로 처리한다")
  void cancel_fails_when_cancels_is_empty() {
    String responseBody =
        """
        {
          "paymentKey": "pgKey_xyz",
          "status": "CANCELED",
          "cancels": []
        }
        """;

    mockServer
        .expect(requestTo(CANCEL_URL))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("응답에 canceledAt이 없으면 통신 실패로 처리한다")
  void cancel_fails_when_canceled_at_is_missing() {
    String responseBody =
        """
        {
          "paymentKey": "pgKey_xyz",
          "status": "CANCELED",
          "cancels": [
            {
              "transactionKey": "TX-CANCEL-1",
              "cancelAmount": 55000
            }
          ]
        }
        """;

    mockServer
        .expect(requestTo(CANCEL_URL))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.cancel(command()))
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

  private PaymentCancelCommand command() {
    return new PaymentCancelCommand(
        PaymentProvider.TOSS, "pgKey_xyz", 55_000L, "단순 변심", "REFUND-0000001");
  }
}
