package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

/**
 * Toss Payments 결제 조회 API 연동 구현체.
 *
 * <p>{@code payment.pg.toss.enabled=true} 일 때만 활성화된다. secret-key 미설정 시 {@link
 * com.ticketrush.global.config.RestClientConfig#tossPaymentRestClient}에서 startup 단계에 실패시킨다.
 *
 * <p>Webhook 재조회 검증 전용이다. paymentKey가 PG에 존재하지 않으면(Toss {@code NOT_FOUND_PAYMENT}) 위조/무효 webhook으로
 * 보고 {@link Optional#empty()}를 반환한다. 그 외 4xx·5xx·timeout은 진위를 판정할 수 없으므로 {@link
 * ErrorStatus#PAYMENT_PG_COMMUNICATION_FAILED}로 던져 상위에서 재전송에 위임하게 한다.
 *
 * <p>응답 body는 {@code exchange}의 {@code bodyTo}로 변환한다(전역 매퍼가 snake_case라, Toss camelCase는 RestClient
 * 자체 메시지 컨버터로 파싱해야 한다).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "payment.pg.toss", name = "enabled", havingValue = "true")
public class TossPaymentInquiryClient implements PaymentInquiryClient {

  private static final String PAYMENT_PATH = "/v1/payments/{paymentKey}";
  private static final String NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT";

  private final RestClient restClient;

  public TossPaymentInquiryClient(@Qualifier("tossPaymentRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public PaymentProvider provider() {
    return PaymentProvider.TOSS;
  }

  @Override
  public Optional<PaymentInquiryResult> inquire(String paymentKey) {
    try {
      return restClient
          .get()
          .uri(PAYMENT_PATH, paymentKey)
          .exchange((request, response) -> handle(response, paymentKey));
    } catch (BusinessException e) {
      throw e;
    } catch (ResourceAccessException e) {
      log.error(
          "[PG-TOSS] 결제 조회 통신 실패(timeout 등). paymentKey={}, message={}",
          mask(paymentKey),
          e.getMessage());
      throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
    } catch (RestClientException e) {
      log.error(
          "[PG-TOSS] 결제 조회 호출 중 예외 발생. paymentKey={}, message={}",
          mask(paymentKey),
          e.getMessage());
      throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
    }
  }

  private Optional<PaymentInquiryResult> handle(
      ConvertibleClientHttpResponse response, String paymentKey) throws IOException {
    HttpStatusCode status = response.getStatusCode();

    if (status.is2xxSuccessful()) {
      return Optional.of(toResult(readBody(response, paymentKey)));
    }

    if (status.is4xxClientError()) {
      String code = readErrorCode(response);
      if (NOT_FOUND_PAYMENT.equals(code)) {
        log.warn(
            "[PG-TOSS] PG에 존재하지 않는 paymentKey. 위조/무효 webhook으로 판정. paymentKey={}",
            mask(paymentKey));
        return Optional.empty();
      }
      // 인증(secret-key 오설정 등) 계열은 재전송으로 해소되지 않는 영구 오류이므로, 재시도 대상인 통신 실패와
      // 분리해 조기에 드러낸다(그 외 4xx는 통신 실패로 보고 non-200으로 재전송에 위임).
      ErrorStatus mapped = TossErrorCode.from(code).getErrorStatus();
      ErrorStatus resolved =
          mapped == ErrorStatus.PAYMENT_PG_AUTH_FAILED
              ? mapped
              : ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED;
      log.error(
          "[PG-TOSS] 결제 조회 거절(4xx). status={}, code={}, mapped={}, paymentKey={}",
          status,
          code,
          resolved,
          mask(paymentKey));
      throw new BusinessException(resolved);
    }

    log.error("[PG-TOSS] 결제 조회 PG 서버 오류. status={}, paymentKey={}", status, mask(paymentKey));
    throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
  }

  private TossPaymentInquiryResponse readBody(
      ConvertibleClientHttpResponse response, String paymentKey) {
    TossPaymentInquiryResponse body = response.bodyTo(TossPaymentInquiryResponse.class);
    if (body == null || !StringUtils.hasText(body.paymentKey())) {
      log.error("[PG-TOSS] 결제 조회 응답에 paymentKey 누락. paymentKey={}", mask(paymentKey));
      throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
    }
    return body;
  }

  private String readErrorCode(ConvertibleClientHttpResponse response) {
    try {
      TossErrorResponse error = response.bodyTo(TossErrorResponse.class);
      return error == null ? null : error.code();
    } catch (RestClientException e) {
      log.warn("[PG-TOSS] 조회 에러 응답 body 파싱 실패. message={}", e.getMessage());
      return null;
    }
  }

  private PaymentInquiryResult toResult(TossPaymentInquiryResponse body) {
    LocalDateTime approvedAt =
        body.approvedAt() == null
            ? null
            : body.approvedAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    return new PaymentInquiryResult(
        body.paymentKey(), body.orderId(), body.totalAmount(), body.status(), approvedAt);
  }

  /** 로그 노출 시 민감한 paymentKey를 앞 4자리만 남기고 마스킹한다. */
  private String mask(String value) {
    if (value == null || value.length() <= 4) {
      return "****";
    }
    return value.substring(0, 4) + "****";
  }
}
