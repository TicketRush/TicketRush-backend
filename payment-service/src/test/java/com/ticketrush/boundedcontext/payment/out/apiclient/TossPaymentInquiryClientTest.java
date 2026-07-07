package com.ticketrush.boundedcontext.payment.out.apiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TossPaymentInquiryClientTest {

  private static final String BASE_URL = "https://api.tosspayments.com";
  private static final String PAYMENT_KEY = "tossKey_abc";
  private static final String INQUIRY_URL = BASE_URL + "/v1/payments/" + PAYMENT_KEY;

  private MockRestServiceServer mockServer;
  private TossPaymentInquiryClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();
    client = new TossPaymentInquiryClient(builder.build());
  }

  @Test
  @DisplayName("PG에 존재하는 결제면 조회 결과를 매핑해 반환한다")
  void inquire_success() {
    String responseBody =
        """
        {
          "paymentKey": "tossKey_abc",
          "orderId": "BKG-0000100",
          "totalAmount": 55000,
          "status": "DONE",
          "approvedAt": "2026-05-22T10:00:00+09:00"
        }
        """;

    mockServer
        .expect(requestTo(INQUIRY_URL))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

    Optional<PaymentInquiryResult> result = client.inquire(PAYMENT_KEY);

    assertThat(result).isPresent();
    assertThat(result.get().paymentKey()).isEqualTo("tossKey_abc");
    assertThat(result.get().status()).isEqualTo("DONE");
    assertThat(result.get().totalAmount()).isEqualTo(55_000L);
    assertThat(result.get().approvedAt()).isNotNull();

    mockServer.verify();
  }

  @Test
  @DisplayName("NOT_FOUND_PAYMENT(4xx)면 위조/무효로 보고 빈 결과를 반환한다")
  void inquire_returns_empty_when_not_found() {
    mockServer
        .expect(requestTo(INQUIRY_URL))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"존재하지 않는 결제입니다.\"}"));

    assertThat(client.inquire(PAYMENT_KEY)).isEmpty();

    mockServer.verify();
  }

  @Test
  @DisplayName("인증 계열 4xx(UNAUTHORIZED_KEY)는 영구 오류로 PAYMENT_502_002 예외가 발생한다")
  void inquire_maps_auth_4xx_to_pg_auth_failed() {
    mockServer
        .expect(requestTo(INQUIRY_URL))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"UNAUTHORIZED_KEY\",\"message\":\"인증 실패\"}"));

    assertThatThrownBy(() -> client.inquire(PAYMENT_KEY))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_AUTH_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("인증이 아닌 그 외 4xx는 진위 판정 불가로 PAYMENT_503_001 예외가 발생한다")
  void inquire_maps_other_4xx_to_communication_failed() {
    mockServer
        .expect(requestTo(INQUIRY_URL))
        .andRespond(
            withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청\"}"));

    assertThatThrownBy(() -> client.inquire(PAYMENT_KEY))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("5xx면 PAYMENT_503_001 예외가 발생한다")
  void inquire_maps_5xx_to_communication_failed() {
    mockServer.expect(requestTo(INQUIRY_URL)).andRespond(withServerError());

    assertThatThrownBy(() -> client.inquire(PAYMENT_KEY))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);

    mockServer.verify();
  }

  @Test
  @DisplayName("응답 body에 paymentKey가 없으면 통신 실패로 처리한다")
  void inquire_fails_when_payment_key_missing() {
    mockServer
        .expect(requestTo(INQUIRY_URL))
        .andRespond(withSuccess("{\"status\":\"DONE\"}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.inquire(PAYMENT_KEY))
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
