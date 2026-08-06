package com.ticketrush.boundedcontext.payment.out.apiclient;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
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

  private static final String IDEMPOTENCY_KEY = "REFUND-0000001";
  private static final String CANCEL_REASON = "단순 변심";

  private static final String SUCCESS_BODY =
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

  private RestClient.Builder builder;
  private MockRestServiceServer mockServer;
  private MeterRegistry meterRegistry;
  private TossPaymentCancelClient client;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();
    meterRegistry = new SimpleMeterRegistry();
    // 대기는 1ms로 주입해 테스트가 실제로 기다리지 않게 한다. 0은 킬 스위치라 재시도 자체가 꺼지므로 쓸 수 없다.
    client = clientWithRetryDelay(1L);
  }

  @AfterEach
  void tearDown() {
    // 인터럽트 테스트가 세운 플래그가 다음 테스트로 새지 않게 한다.
    Thread.interrupted();
  }

  @Test
  @DisplayName("Toss 결제 취소 성공 시 최신 cancel 항목을 매핑하고 멱등 키 헤더를 전송한다")
  void cancel_success() {
    expectSuccess();

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
    expect4xxWithCode(HttpStatus.BAD_REQUEST, "ALREADY_CANCELED_PAYMENT");

    assertThatThrownBy(() -> client.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_FAILED);

    assertThat(totalRetryCount()).isZero();
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
  @DisplayName("IDEMPOTENT_REQUEST_PROCESSING(409)은 재시도해 성공하면 정상 결과를 반환한다")
  void cancel_retries_idempotent_request_processing_and_succeeds() {
    expect4xxWithCode(HttpStatus.CONFLICT, "IDEMPOTENT_REQUEST_PROCESSING");
    expectSuccess();

    PaymentCancelResult result = client.cancel(command());

    assertThat(result.pgRefundKey()).isEqualTo("TX-CANCEL-1");
    assertThat(retryCount("IDEMPOTENT_REQUEST_PROCESSING", "succeeded")).isEqualTo(1.0);
    mockServer.verify();
  }

  @Test
  @DisplayName("PROVIDER_ERROR(400)도 재시도 대상이다")
  void cancel_retries_provider_error() {
    expect4xxWithCode(HttpStatus.BAD_REQUEST, "PROVIDER_ERROR");
    expectSuccess();

    PaymentCancelResult result = client.cancel(command());

    assertThat(result.refundedAmount()).isEqualTo(55_000L);
    assertThat(retryCount("PROVIDER_ERROR", "succeeded")).isEqualTo(1.0);
    mockServer.verify();
  }

  @Test
  @DisplayName("FORBIDDEN_CONSECUTIVE_REQUEST(403)도 재시도 대상이다 — Toss에 429가 없어 이 코드가 rate limit이다")
  void cancel_retries_forbidden_consecutive_request() {
    expect4xxWithCode(HttpStatus.FORBIDDEN, "FORBIDDEN_CONSECUTIVE_REQUEST");
    expectSuccess();

    PaymentCancelResult result = client.cancel(command());

    assertThat(result.refundedAmount()).isEqualTo(55_000L);
    assertThat(retryCount("FORBIDDEN_CONSECUTIVE_REQUEST", "succeeded")).isEqualTo(1.0);
    mockServer.verify();
  }

  @Test
  @DisplayName("재시도 요청은 첫 시도와 동일한 멱등 키·body를 보낸다 — 이 불변식이 이중 환불을 막는다")
  void cancel_retry_reuses_same_idempotency_key_and_body() {
    // expect4xxWithCode·expectSuccess 모두 멱등 키 헤더와 cancelReason을 단언하므로,
    // 두 요청 중 하나라도 키나 body가 달라지면 이 테스트가 깨진다.
    expect4xxWithCode(HttpStatus.CONFLICT, "IDEMPOTENT_REQUEST_PROCESSING");
    expectSuccess();

    client.cancel(command());

    mockServer.verify();
  }

  @Test
  @DisplayName("재시도까지 같은 거절이면 PAYMENT_502_003으로 확정해 FAILED 이력·보상 경로로 넘긴다")
  void cancel_confirms_refund_failed_when_retry_is_exhausted() {
    expect4xxWithCode(HttpStatus.CONFLICT, "IDEMPOTENT_REQUEST_PROCESSING");
    expect4xxWithCode(HttpStatus.CONFLICT, "IDEMPOTENT_REQUEST_PROCESSING");

    assertThatThrownBy(() -> client.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_FAILED);

    assertThat(retryCount("IDEMPOTENT_REQUEST_PROCESSING", "exhausted")).isEqualTo(1.0);
    mockServer.verify();
  }

  @Test
  @DisplayName("재시도가 통신 실패로 끝나면 그 분류를 전파하고 outcome=failed로 센다 — 소진과 구분한다")
  void cancel_propagates_communication_failure_from_retry() {
    expect4xxWithCode(HttpStatus.CONFLICT, "IDEMPOTENT_REQUEST_PROCESSING");
    mockServer.expect(requestTo(CANCEL_URL)).andRespond(withServerError());

    assertThatThrownBy(() -> client.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);

    assertThat(retryCount("IDEMPOTENT_REQUEST_PROCESSING", "failed")).isEqualTo(1.0);
    mockServer.verify();
  }

  @Test
  @DisplayName("재시도에서 다른 결정적 코드가 오면 outcome=failed다 — 소진으로 접으면 의미가 뒤집힌다")
  void cancel_counts_different_rejection_from_retry_as_failed() {
    expect4xxWithCode(HttpStatus.CONFLICT, "IDEMPOTENT_REQUEST_PROCESSING");
    expect4xxWithCode(HttpStatus.BAD_REQUEST, "ALREADY_CANCELED_PAYMENT");

    assertThatThrownBy(() -> client.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_FAILED);

    assertThat(retryCount("IDEMPOTENT_REQUEST_PROCESSING", "failed")).isEqualTo(1.0);
    mockServer.verify();
  }

  @Test
  @DisplayName("대기 값이 0 이하면 킬 스위치로 동작해 재시도 없이 환불 실패로 확정한다")
  void cancel_skips_retry_when_delay_is_disabled() {
    TossPaymentCancelClient disabled = clientWithRetryDelay(0L);
    expect4xxWithCode(HttpStatus.CONFLICT, "IDEMPOTENT_REQUEST_PROCESSING");

    assertThatThrownBy(() -> disabled.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_FAILED);

    assertThat(retryCount("IDEMPOTENT_REQUEST_PROCESSING", "disabled")).isEqualTo(1.0);
    // PG를 두 번 부르지 않았음을 호출 기대 1건으로 고정한다.
    mockServer.verify();
  }

  @Test
  @DisplayName("재시도 대기 중 인터럽트되면 통신 실패로 던지고 outcome=failed로 센다")
  void cancel_maps_interrupted_retry_delay_to_communication_failure() {
    // 1차 응답을 돌려주는 시점에 플래그를 세운다. 호출 전에 세우면 1차 요청 처리 자체가 영향받을 수 있다.
    // 플래그가 선 상태로 진입한 Thread.sleep은 대기 없이 즉시 InterruptedException을 던진다.
    //
    // 전제: 응답 생성부터 Thread.sleep 사이에 인터럽트를 소비하는 연산이 없어야 한다(현재 경로는
    // ByteArrayInputStream 기반 Jackson 파싱 + 동기 콘솔 appender뿐이라 성립). 그 전제가 깨지면 이
    // 테스트는 대기 시간만큼 멈춘 뒤 "No further requests expected" AssertionError로 엉뚱하게 실패한다.
    // 회귀 시 진단 비용을 줄이려고 대기값을 짧게 잡았다.
    TossPaymentCancelClient slow = clientWithRetryDelay(2_000L);
    mockServer
        .expect(requestTo(CANCEL_URL))
        .andRespond(
            request -> {
              Thread.currentThread().interrupt();
              return withStatus(HttpStatus.CONFLICT)
                  .contentType(MediaType.APPLICATION_JSON)
                  .body("{\"code\":\"IDEMPOTENT_REQUEST_PROCESSING\",\"message\":\"처리중\"}")
                  .createResponse(request);
            });

    assertThatThrownBy(() -> slow.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);

    assertThat(retryCount("IDEMPOTENT_REQUEST_PROCESSING", "failed")).isEqualTo(1.0);
    // 2차 호출에 도달하지 못했으므로 기대는 1건뿐이다.
    mockServer.verify();
  }

  @Test
  @DisplayName("같은 403이라도 NOT_CANCELABLE_PAYMENT는 재시도 없이 즉시 확정한다")
  void cancel_does_not_retry_deterministic_403() {
    expect4xxWithCode(HttpStatus.FORBIDDEN, "NOT_CANCELABLE_PAYMENT");

    assertThatThrownBy(() -> client.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_FAILED);

    assertThat(totalRetryCount()).isZero();
    mockServer.verify();
  }

  @Test
  @DisplayName("인증 실패(UNAUTHORIZED_KEY 401)는 재시도하지 않고 FAILED 이력 경로를 유지한다")
  void cancel_does_not_retry_auth_failure() {
    expect4xxWithCode(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED_KEY");

    assertThatThrownBy(() -> client.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_FAILED);

    assertThat(totalRetryCount()).isZero();
    mockServer.verify();
  }

  @Test
  @DisplayName("화이트리스트에 없는 미정의 코드는 재시도하지 않고 기존 동작을 유지한다")
  void cancel_does_not_retry_undefined_code() {
    expect4xxWithCode(HttpStatus.BAD_REQUEST, "SOME_UNDEFINED_TOSS_CODE");

    assertThatThrownBy(() -> client.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_FAILED);

    assertThat(totalRetryCount()).isZero();
    mockServer.verify();
  }

  @Test
  @DisplayName("4xx 응답 body를 읽지 못하면 재시도 여부를 판정할 수 없으므로 결정적 거절로 떨어진다")
  void cancel_treats_unparsable_error_body_as_deterministic() {
    mockServer
        .expect(requestTo(CANCEL_URL))
        .andRespond(
            withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("not-a-json"));

    assertThatThrownBy(() -> client.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_FAILED);

    assertThat(totalRetryCount()).isZero();
    mockServer.verify();
  }

  @Test
  @DisplayName("body가 빈 4xx도 결정적 거절로 떨어진다")
  void cancel_treats_empty_error_body_as_deterministic() {
    mockServer
        .expect(requestTo(CANCEL_URL))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.cancel(command()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.PAYMENT_REFUND_FAILED);

    assertThat(totalRetryCount()).isZero();
    mockServer.verify();
  }

  @Test
  @DisplayName("TOSS provider를 담당한다")
  void provider_is_toss() {
    assertThat(client.provider()).isEqualTo(PaymentProvider.TOSS);
    assertThat(client.isFallback()).isFalse();
  }

  private TossPaymentCancelClient clientWithRetryDelay(long retryDelayMs) {
    return new TossPaymentCancelClient(
        builder.build(), new ObjectMapper(), meterRegistry, retryDelayMs);
  }

  /** 성공 응답 기대. 멱등 키·body를 함께 단언해 재시도가 동일 요청임을 고정한다. */
  private void expectSuccess() {
    mockServer
        .expect(requestTo(CANCEL_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", IDEMPOTENCY_KEY))
        .andExpect(jsonPath("$.cancelReason").value(CANCEL_REASON))
        .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));
  }

  /** 4xx 거절 응답 기대. 멱등 키·body를 함께 단언해 재시도가 동일 요청임을 고정한다. */
  private void expect4xxWithCode(HttpStatus status, String tossCode) {
    mockServer
        .expect(requestTo(CANCEL_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", IDEMPOTENCY_KEY))
        .andExpect(jsonPath("$.cancelReason").value(CANCEL_REASON))
        .andRespond(
            withStatus(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"" + tossCode + "\",\"message\":\"테스트 거절 메시지\"}"));
  }

  private double retryCount(String tossCode, String outcome) {
    return meterRegistry
        .get(MetricNames.PAYMENT_PG_CANCEL_RETRY)
        .tag(MetricNames.TAG_REASON, tossCode)
        .tag(MetricNames.TAG_OUTCOME, outcome)
        .counter()
        .count();
  }

  /** 재시도 카운터가 어떤 태그로도 기록되지 않았는지 확인한다(재시도 미발생 검증용). */
  private double totalRetryCount() {
    return meterRegistry.find(MetricNames.PAYMENT_PG_CANCEL_RETRY).counters().stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }

  private PaymentCancelCommand command() {
    return new PaymentCancelCommand(
        PaymentProvider.TOSS, "pgKey_xyz", 55_000L, CANCEL_REASON, IDEMPOTENCY_KEY);
  }
}
