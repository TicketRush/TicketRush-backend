package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * Toss Payments 결제 승인 API 연동 구현체.
 *
 * <p>{@code payment.pg.toss.enabled=true} 일 때만 활성화된다. secret-key 미설정 시 {@link
 * com.ticketrush.global.config.RestClientConfig#tossPaymentRestClient}에서 startup 단계에 실패시킨다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "payment.pg.toss", name = "enabled", havingValue = "true")
public class TossPaymentApprovalClient implements PaymentApprovalClient {

  private static final String CONFIRM_PATH = "/v1/payments/confirm";

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public TossPaymentApprovalClient(
      @Qualifier("tossPaymentRestClient") RestClient restClient, ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public PaymentProvider provider() {
    return PaymentProvider.TOSS;
  }

  @Override
  public PaymentApprovalResponse approve(PaymentApprovalRequest request) {
    TossConfirmRequest body =
        new TossConfirmRequest(request.paymentKey(), request.orderId(), request.amount());

    try {
      TossConfirmResponse response =
          restClient
              .post()
              .uri(CONFIRM_PATH)
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .onStatus(
                  HttpStatusCode::is4xxClientError,
                  (req, res) -> {
                    TossErrorResponse errorBody = readErrorBody(res);
                    String tossCode = errorBody == null ? null : errorBody.code();
                    String tossMessage = errorBody == null ? null : errorBody.message();
                    TossErrorCode mapped = TossErrorCode.from(tossCode);
                    log.warn(
                        "[PG-TOSS] 결제 승인 거절. status={}, tossCode={}, mapped={}, "
                            + "orderId={}, bookingId={}",
                        res.getStatusCode(),
                        tossCode,
                        mapped,
                        request.orderId(),
                        request.bookingId());
                    // 원본 Toss code/message를 UseCase까지 전달해 FAILED 이력에 남긴다(#332). data는 채우지
                    // 않아(super(errorStatus)) 원본이 HTTP 응답 body에 노출되지 않게 한다.
                    throw new PgRejectionException(mapped.getErrorStatus(), tossCode, tossMessage);
                  })
              .onStatus(
                  HttpStatusCode::is5xxServerError,
                  (req, res) -> {
                    log.error(
                        "[PG-TOSS] PG 서버 오류. status={}, orderId={}, bookingId={}",
                        res.getStatusCode(),
                        request.orderId(),
                        request.bookingId());
                    throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
                  })
              .body(TossConfirmResponse.class);

      if (response == null) {
        log.error(
            "[PG-TOSS] 응답이 비어있습니다. orderId={}, bookingId={}",
            request.orderId(),
            request.bookingId());
        throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
      }

      if (response.approvedAt() == null) {
        log.error(
            "[PG-TOSS] 응답에 approvedAt 누락. orderId={}, bookingId={}",
            request.orderId(),
            request.bookingId());
        throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
      }

      /* 쓸 만한 값을 우선 고르고(#619), 둘 다 쓸 만하지 않으면 non-null인 쪽이라도 그대로 쓴다. 마지막 단계가 필요한
       * 이유는 여기가 PG 승인(과금) 이후이기 때문이다 — 표시용 필드가 비었다는 이유로 실패시키면 과금은 됐는데 payment
       * row도 FAILED 이력도 남지 않는다(PAYMENT_PG_COMMUNICATION_FAILED는 RECORDABLE_FAILURES에 없다).
       * 그래서 throw는 두 값이 모두 null이라 저장할 것이 정말 아무것도 없을 때로만 좁힌다. */
      String approvalNumber;
      if (StringUtils.hasText(response.transactionKey())) {
        approvalNumber = response.transactionKey();
      } else if (StringUtils.hasText(response.paymentKey())) {
        approvalNumber = response.paymentKey();
      } else {
        approvalNumber =
            response.transactionKey() != null ? response.transactionKey() : response.paymentKey();
      }
      if (approvalNumber == null) {
        log.error(
            "[PG-TOSS] 응답에 transactionKey, paymentKey 모두 누락. orderId={}, bookingId={}",
            request.orderId(),
            request.bookingId());
        throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
      }
      if (approvalNumber.length() > Payment.APPROVAL_NUMBER_MAX_LENGTH) {
        // 이 로그가 뜬다는 것은 Toss가 상한을 넘는 식별자를 내려보내기 시작했다는 뜻이므로, 저장이 어떻게 처리되든 계약
        // 변화 자체를 봐야 한다. 지금 스펙대로면 넘을 수 있는 것은 paymentKey(200자)뿐이고 transactionKey는 64자라
        // 넘지 않지만, 그 전제가 틀린 날을 대비해 값의 출처를 함께 남겨 사후에 반증할 수 있게 한다.
        log.warn(
            "[PG-TOSS] 승인번호가 저장 상한({}자)을 넘었다. Toss 응답 계약 변화 가능성. "
                + "source={}, length={}, orderId={}, bookingId={}",
            Payment.APPROVAL_NUMBER_MAX_LENGTH,
            approvalNumber.equals(response.transactionKey()) ? "transactionKey" : "paymentKey",
            approvalNumber.length(),
            request.orderId(),
            request.bookingId());
      }

      LocalDateTime approvedAt =
          response.approvedAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();

      /* method는 Toss 명세상 nullable이라 위 세 가드와 달리 승인을 실패시키지 않는다. 결제수단은 보존 대상일 뿐
       * 승인 성립 요건이 아니다(#593). 평상시 소음을 만들지 않도록 debug로만 남긴다. */
      if (response.method() == null) {
        log.debug(
            "[PG-TOSS] 응답에 method 없음. orderId={}, bookingId={}",
            request.orderId(),
            request.bookingId());
      }

      return new PaymentApprovalResponse(
          approvalNumber, response.totalAmount(), approvedAt, response.method());

    } catch (BusinessException e) {
      throw e;
    } catch (ResourceAccessException e) {
      log.error(
          "[PG-TOSS] PG 통신 실패(timeout 등). orderId={}, bookingId={}, message={}",
          request.orderId(),
          request.bookingId(),
          e.getMessage());
      throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
    } catch (RestClientException e) {
      log.error(
          "[PG-TOSS] PG 호출 중 예외 발생. orderId={}, bookingId={}, message={}",
          request.orderId(),
          request.bookingId(),
          e.getMessage());
      throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
    }
  }

  private TossErrorResponse readErrorBody(ClientHttpResponse response) {
    try {
      return objectMapper.readValue(response.getBody(), TossErrorResponse.class);
    } catch (IOException e) {
      log.warn("[PG-TOSS] 에러 응답 body 파싱 실패. message={}", e.getMessage());
      return null;
    }
  }
}
