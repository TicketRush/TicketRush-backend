package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
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
 * <p>PG 거절(4xx)은 {@link ErrorStatus#PAYMENT_REFUND_FAILED}, 통신 실패(5xx/timeout)는 {@link
 * ErrorStatus#PAYMENT_PG_COMMUNICATION_FAILED}로 매핑한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "payment.pg.toss", name = "enabled", havingValue = "true")
public class TossPaymentCancelClient implements PaymentCancelClient {

  private static final String CANCEL_PATH = "/v1/payments/{paymentKey}/cancel";
  private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private final RestClient restClient;

  public TossPaymentCancelClient(@Qualifier("tossPaymentRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public PaymentProvider provider() {
    return PaymentProvider.TOSS;
  }

  @Override
  public PaymentCancelResult cancel(PaymentCancelCommand command) {
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
                    log.warn(
                        "[PG-TOSS] 결제 취소 거절. status={}, paymentKey={}",
                        res.getStatusCode(),
                        mask(command.paymentKey()));
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
}
