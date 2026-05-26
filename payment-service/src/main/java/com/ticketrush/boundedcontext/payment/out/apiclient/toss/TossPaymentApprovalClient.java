package com.ticketrush.boundedcontext.payment.out.apiclient.toss;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalClient;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalRequest;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalResponse;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
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
@Order(0)
@ConditionalOnProperty(prefix = "payment.pg.toss", name = "enabled", havingValue = "true")
public class TossPaymentApprovalClient implements PaymentApprovalClient {

  private static final String CONFIRM_PATH = "/v1/payments/confirm";

  private final RestClient restClient;

  public TossPaymentApprovalClient(@Qualifier("tossPaymentRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public boolean supports(PaymentProvider provider) {
    return provider == PaymentProvider.TOSS;
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
                    log.warn(
                        "[PG-TOSS] 결제 승인 거절. status={}, orderId={}, bookingId={}",
                        res.getStatusCode(),
                        request.orderId(),
                        request.bookingId());
                    throw new BusinessException(ErrorStatus.PAYMENT_APPROVAL_FAILED);
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

      String approvalNumber =
          response.transactionKey() != null ? response.transactionKey() : response.paymentKey();
      if (approvalNumber == null) {
        log.error(
            "[PG-TOSS] 응답에 transactionKey, paymentKey 모두 누락. orderId={}, bookingId={}",
            request.orderId(),
            request.bookingId());
        throw new BusinessException(ErrorStatus.PAYMENT_PG_COMMUNICATION_FAILED);
      }

      LocalDateTime approvedAt =
          response.approvedAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();

      return new PaymentApprovalResponse(approvalNumber, response.totalAmount(), approvedAt);

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
}
