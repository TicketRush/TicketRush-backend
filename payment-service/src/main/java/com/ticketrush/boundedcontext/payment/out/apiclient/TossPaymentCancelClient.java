package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Toss Payments 결제 취소(환불) API 연동 구현체.
 *
 * <p>{@code payment.pg.toss.enabled=true} 일 때만 활성화된다. secret-key 미설정 시 {@link
 * com.ticketrush.global.config.RestClientConfig#tossPaymentRestClient}에서 startup 단계에 실패시킨다.
 *
 * <p><b>4xx 매핑(#573)</b>: 응답 body의 원본 {@code code}를 읽어 갈린다. {@link TossCancelRetryableCode}에 속하면 짧은
 * 대기 후 <b>한 번 더 호출</b>하고, 재시도까지 실패하면 {@link ErrorStatus#PAYMENT_REFUND_FAILED}로 확정한다. 화이트리스트 밖 코드와
 * body를 읽지 못한 응답도 같은 {@code PAYMENT_REFUND_FAILED}다. 통신 실패(5xx/timeout)는 {@link
 * ErrorStatus#PAYMENT_PG_COMMUNICATION_FAILED}로 매핑한다.
 *
 * <p><b>재시도를 이 계층에서 흡수하는 근거(ADR 0012)</b>: 일시적 실패를 예외로 재던져 Kafka 재시도→DLT에 맡기는 것이 레포 표준(#269)이지만,
 * 환불에는 적용하지 않는다. DLT로 간 건은 FAILED 이력도 {@code refundFailedAt}도 남지 않아 관리자 재환불 API가 봉쇄되고, {@code
 * dead_letter_record}는 30일 뒤 삭제된다. 재시도를 여기서 흡수하고 소진 시 <b>기존 확정 경로로 낙하</b>시키면 자동 복구를 얻으면서 복구 창구를 그대로
 * 유지한다.
 *
 * <p><b>{@code payment.pg.toss.cancel-retry-delay-ms}(기본 1000)는 대기 시간이자 킬 스위치다.</b> {@code 0} 이하로
 * 두면 대기만 생략하는 것이 아니라 <b>재시도 자체를 하지 않고</b> 기존 확정 경로로 곧장 낙하한다. "대기 없는 즉시 재시도"를 허용하지 않는 이유는 이 노브가
 * 필요해지는 상황이 하필 {@code FORBIDDEN_CONSECUTIVE_REQUEST}(Toss의 rate limit) 버스트이기 때문이다 — 거기서 대기만 없앤 재시도는
 * PG 호출량을 두 배로 늘려 사고를 키운다. 값은 생성자 주입이라 <b>변경에 재기동이 필요하다</b>(레포에 config
 * server·{@code @RefreshScope}가 없다).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "payment.pg.toss", name = "enabled", havingValue = "true")
public class TossPaymentCancelClient implements PaymentCancelClient {

  private static final String CANCEL_PATH = "/v1/payments/{paymentKey}/cancel";
  private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  /** 재시도가 성공해 정상 결과를 반환했다. */
  private static final String RETRY_OUTCOME_SUCCEEDED = "succeeded";

  /** 재시도에서도 같은 부류(재시도 가능 코드)의 거절이 돌아와 환불 실패로 확정했다. */
  private static final String RETRY_OUTCOME_EXHAUSTED = "exhausted";

  /**
   * 재시도가 <b>다른</b> 실패로 끝났다 — 통신 오류, 다른 결정적 거절 코드, 대기 중 인터럽트.
   *
   * <p>{@code exhausted}와 반드시 구분한다. 이 카운터의 존재 목적이 "재시도 유효성이 문서로 확인되지 않은 {@code PROVIDER_ERROR}가 실제로
   * 효과가 있는가"의 사후 판정인데, 성격이 다른 결말을 한 값에 접으면 그 판정이 오염된다. 특히 409 뒤 재시도에서 {@code
   * ALREADY_CANCELED_PAYMENT}가 오는 경우는 <b>선행 요청이 성공했다는 뜻</b>이라 의미가 정반대다.
   */
  private static final String RETRY_OUTCOME_FAILED = "failed";

  /** 킬 스위치({@code cancel-retry-delay-ms <= 0})로 재시도를 건너뛰었다. */
  private static final String RETRY_OUTCOME_DISABLED = "disabled";

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final long retryDelayMs;

  public TossPaymentCancelClient(
      @Qualifier("tossPaymentRestClient") RestClient restClient,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      @Value("${payment.pg.toss.cancel-retry-delay-ms:1000}") long retryDelayMs) {
    this.restClient = restClient;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.retryDelayMs = retryDelayMs;
  }

  @Override
  public PaymentProvider provider() {
    return PaymentProvider.TOSS;
  }

  @Override
  public PaymentCancelResult cancel(PaymentCancelCommand command) {
    try {
      return attemptCancel(command);
    } catch (RetryableRejection rejection) {
      if (retryDelayMs <= 0) {
        countRetry(rejection.tossCode(), RETRY_OUTCOME_DISABLED);
        log.warn(
            "[PG-TOSS] 재시도 비활성(cancel-retry-delay-ms<=0). 환불 실패로 확정한다. tossCode={}, paymentKey={}",
            rejection.tossCode(),
            mask(command.paymentKey()));
        throw new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED);
      }
      return retryOnce(command, rejection.tossCode());
    }
  }

  /**
   * 재시도 대상 거절을 만난 뒤 한 번만 더 호출한다. 요청은 첫 시도와 <b>완전히 동일</b>하다 — 같은 {@code command}를 쓰므로 결제당 고정 멱등
   * 키({@code REFUND-%07d})가 유지되고, 그래서 재호출이 중복 환불을 만들지 않는다.
   *
   * <p>재시도까지 같은 부류로 실패하면 {@link ErrorStatus#PAYMENT_REFUND_FAILED}로 바꿔 던진다. 그래야 {@code
   * FailedRefundRecorder.isDeterministicRejection}이 true가 되어 FAILED 이력과 보상 이벤트가 기존과 동일하게 남는다. 재시도 중
   * 다른 실패(통신 오류 등)가 나오면 그 예외를 그대로 전파해 원래 분류를 따르게 한다.
   *
   * <p><b>전제: {@code retryDelayMs > 0}일 때만 호출된다.</b> {@link #cancel}의 킬 스위치 분기가 이를 보장한다. 전제가 깨지면
   * {@link #sleepBeforeRetry}의 {@code Thread.sleep}이 음수 인자로 {@code IllegalArgumentException}을 던지고,
   * 그 예외는 {@code ErrorStatus}를 갖지 않은 채 {@code cancel} 밖으로 나간다 — 이 클래스가 통째로 막으려는 그 상황이다.
   */
  private PaymentCancelResult retryOnce(PaymentCancelCommand command, String tossCode) {
    log.warn(
        "[PG-TOSS] 재시도 가능한 취소 거절. 대기 후 1회 재시도한다. tossCode={}, delayMs={}, paymentKey={}",
        tossCode,
        retryDelayMs,
        mask(command.paymentKey()));

    try {
      sleepBeforeRetry();
      PaymentCancelResult result = attemptCancel(command);
      countRetry(tossCode, RETRY_OUTCOME_SUCCEEDED);
      log.info(
          "[PG-TOSS] 취소 재시도 성공. tossCode={}, paymentKey={}", tossCode, mask(command.paymentKey()));
      return result;
    } catch (RetryableRejection retried) {
      countRetry(tossCode, RETRY_OUTCOME_EXHAUSTED);
      log.warn(
          "[PG-TOSS] 취소 재시도 소진. 환불 실패로 확정한다. tossCode={}, retriedCode={}, paymentKey={}",
          tossCode,
          retried.tossCode(),
          mask(command.paymentKey()));
      throw new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED);
    } catch (RuntimeException other) {
      countRetry(tossCode, RETRY_OUTCOME_FAILED);
      log.warn(
          "[PG-TOSS] 취소 재시도 미완결(다른 실패 또는 대기 인터럽트). 분류를 그대로 전파한다. tossCode={}, paymentKey={}",
          tossCode,
          mask(command.paymentKey()),
          other);
      throw other;
    }
  }

  private PaymentCancelResult attemptCancel(PaymentCancelCommand command) {
    TossCancelRequest body = new TossCancelRequest(command.reason());

    try {
      TossCancelResponse response =
          restClient
              .post()
              .uri(CANCEL_PATH, command.paymentKey())
              .contentType(MediaType.APPLICATION_JSON)
              .header(IDEMPOTENCY_KEY_HEADER, command.idempotencyKey())
              .body(body)
              .retrieve()
              .onStatus(
                  HttpStatusCode::is4xxClientError,
                  (req, res) -> {
                    TossErrorResponse errorBody = readErrorBody(res);
                    String tossCode = errorBody == null ? null : errorBody.code();
                    boolean retryable = TossCancelRetryableCode.isRetryable(tossCode);
                    log.warn(
                        "[PG-TOSS] 결제 취소 거절. status={}, tossCode={}, retryable={}, paymentKey={}",
                        res.getStatusCode(),
                        tossCode,
                        retryable,
                        mask(command.paymentKey()));
                    if (retryable) {
                      throw new RetryableRejection(tossCode);
                    }
                    throw new BusinessException(ErrorStatus.PAYMENT_REFUND_FAILED);
                  })
              .onStatus(
                  HttpStatusCode::is5xxServerError,
                  (req, res) -> {
                    log.error(
                        "[PG-TOSS] PG 서버 오류. status={}, paymentKey={}",
                        res.getStatusCode(),
                        mask(command.paymentKey()));
                    throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
                  })
              .body(TossCancelResponse.class);

      return toResult(response, command);

    } catch (BusinessException e) {
      throw e;
    } catch (ResourceAccessException e) {
      throw toCommunicationFailure("PG 통신 실패(timeout 등)", command, e);
    } catch (RestClientException e) {
      throw toCommunicationFailure("PG 호출 중 예외 발생", command, e);
    }
  }

  /**
   * 재시도 전 대기한다.
   *
   * <p>인터럽트되면 {@link ErrorStatus#PAYMENT_PG_COMMUNICATION_FAILED}로 던진다. 2차 시도를 하지 않았으므로 "PG가 거절했다"로
   * 확정할 수는 없고(1차 거절은 {@code IDEMPOTENT_REQUEST_PROCESSING}처럼 <b>아직 진행 중</b>이라는 뜻일 수 있다), 그렇다고 성공으로
   * 볼 수도 없다. 인터럽트는 사실상 애플리케이션 종료 시점이며 이때 컨슈머는 아직 ack하지 않았으므로 <b>메시지가 재전달되어 다음 기동에서 다시 처리된다</b> —
   * 이벤트 경로에서는 이력이 사라지지 않는다. API 취소 경로에는 재전달이 없지만, 종료 중이라 사용자 응답 자체가 전달되지 않고 사용자는 다시 시도할 수 있다.
   */
  private void sleepBeforeRetry() {
    try {
      Thread.sleep(retryDelayMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
    }
  }

  private void countRetry(String tossCode, String outcome) {
    Counter.builder(MetricNames.PAYMENT_PG_CANCEL_RETRY)
        .tag(MetricNames.TAG_REASON, tossCode)
        .tag(MetricNames.TAG_OUTCOME, outcome)
        .register(meterRegistry)
        .increment();
  }

  private TossErrorResponse readErrorBody(ClientHttpResponse response) {
    try {
      return objectMapper.readValue(response.getBody(), TossErrorResponse.class);
    } catch (IOException e) {
      // body를 읽지 못하면 재시도 여부를 판정할 수 없다. 화이트리스트 밖으로 떨어져 기존 동작(결정적 거절)을 유지한다.
      log.warn("[PG-TOSS] 취소 에러 응답 body 파싱 실패. message={}", e.getMessage());
      return null;
    }
  }

  private PaymentCancelResult toResult(TossCancelResponse response, PaymentCancelCommand command) {
    if (response == null || response.cancels() == null || response.cancels().isEmpty()) {
      log.error("[PG-TOSS] 취소 응답에 cancels 누락. paymentKey={}", mask(command.paymentKey()));
      throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
    }

    List<TossCancelResponse.Cancel> cancels = response.cancels();
    TossCancelResponse.Cancel latest = cancels.get(cancels.size() - 1);

    if (latest.canceledAt() == null) {
      log.error("[PG-TOSS] 취소 응답에 canceledAt 누락. paymentKey={}", mask(command.paymentKey()));
      throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
    }

    String pgRefundKey =
        StringUtils.hasText(latest.transactionKey())
            ? latest.transactionKey()
            : response.paymentKey();

    LocalDateTime canceledAt =
        latest.canceledAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();

    return new PaymentCancelResult(pgRefundKey, latest.cancelAmount(), canceledAt);
  }

  /** 동일하게 {@link ErrorStatus#PAYMENT_PG_COMMUNICATION_FAILED}로 매핑되는 통신 예외의 로깅·변환을 공통화한다. */
  private BusinessException toCommunicationFailure(
      String context, PaymentCancelCommand command, Exception e) {
    log.error(
        "[PG-TOSS] {}. paymentKey={}, message={}",
        context,
        mask(command.paymentKey()),
        e.getMessage());
    return new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
  }

  /** 로그 노출 시 민감한 paymentKey를 앞 4자리만 남기고 마스킹한다. */
  private String mask(String value) {
    if (value == null || value.length() <= 4) {
      return "****";
    }
    return value.substring(0, 4) + "****";
  }

  /**
   * 재시도 대상 거절을 {@link #cancel} 안에서만 식별하기 위한 내부 신호.
   *
   * <p><b>{@link BusinessException}을 상속하지 않는다.</b> 이 예외가 클라이언트 밖으로 새어 나가면 {@code
   * FailedRefundRecorder.isDeterministicRejection}이 {@code getErrorStatus()}로 판정할 수 없어 FAILED 이력과
   * 보상 이벤트의 짝(#334 불변식)이 깨진다. {@code cancel}은 이 예외를 반드시 소비하고 {@code ErrorStatus}를 가진 예외로만 밖에 던진다.
   */
  private static final class RetryableRejection extends RuntimeException {

    private final transient String tossCode;

    private RetryableRejection(String tossCode) {
      super(null, null, false, false);
      this.tossCode = tossCode;
    }

    private String tossCode() {
      return tossCode;
    }
  }
}
