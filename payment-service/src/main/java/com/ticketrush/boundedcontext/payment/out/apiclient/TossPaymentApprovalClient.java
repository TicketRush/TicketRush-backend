package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import tools.jackson.databind.json.JsonMapper;

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

  /**
   * Toss가 보내는 camelCase 에러 body 전용 매퍼다.
   *
   * <p><b>주입받지 않는 것이 요점이다.</b> Jackson 2 {@code ObjectMapper}를 주입받던 시절, Spring Boot 4가 그 타입 빈을 등록하지
   * 않아 {@code toss.enabled=true}로 켜는 순간 기동이 통째로 실패했다(#657). 프레임워크가 제공하는 매퍼 타입에 결합하지 않으면 그 사고가 되풀이될
   * 수 없다.
   *
   * <p>전역 매퍼를 쓰지 않는 이유는 따로 있다. 전역은 snake_case인데 Toss는 camelCase로 보낸다. 지금 {@link TossErrorResponse}는
   * {@code code}·{@code message} 둘 다 단어 하나라 어느 정책으로 읽어도 결과가 같지만, 그건 이 DTO가 우연히 안전한 것이지 설계가 안전한 것이
   * 아니다. 필드가 하나라도 늘면 조용히 null이 된다. {@code PaymentWebhookUseCase}가 같은 판단으로 전용 매퍼를 둔다.
   */
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  public TossPaymentApprovalClient(@Qualifier("tossPaymentRestClient") RestClient restClient) {
    this.restClient = restClient;
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
          response.approvedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();

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

  /**
   * body를 읽지 못하면 null을 돌려 {@code TossErrorCode.from(null)}의 폴백 매핑으로 진행한다.
   *
   * <p><b>catch가 {@code Exception}인 것은 의도한 것이다.</b> Jackson 3의 파싱 실패({@code JacksonException})는
   * unchecked라 {@code catch(IOException)}으로 잡히지 않는데, {@code response.getBody()}가 {@code
   * IOException}을 선언하는 탓에 그 catch가 <b>그대로 컴파일된다</b> — 예외 처리가 무력화된 것을 컴파일러가 알려주지 않는다(#657).
   *
   * <p>여기서 놓친 예외는 {@code RestClientException}으로 감싸이지도 않아 {@code approve}의 catch 체인을 그대로 통과한다. 결말은
   * <b>4xx 거절이 raw 예외로 새어 500이 되는 것</b> 하나다 — 거절인데 서버 장애로 표시되고 Toss 원본 code/message 로그도 함께 잃는다. 넓게
   * 잡고 null로 닫는 편이 실패 비용이 작다.
   *
   * <p>예외 타입을 열거하는 멀티캐치를 쓰지 않는 이유는 오늘 동작하지 않아서가 아니라, Jackson이 던지는 타입이 늘었을 때 <b>컴파일 에러 없이</b> 목록 밖
   * 예외를 놓치게 되기 때문이다.
   */
  private TossErrorResponse readErrorBody(ClientHttpResponse response) {
    try {
      return jsonMapper.readValue(response.getBody(), TossErrorResponse.class);
    } catch (Exception e) {
      log.warn(
          "[PG-TOSS] 에러 응답 body 파싱 실패. exception={}, message={}",
          e.getClass().getSimpleName(),
          e.getMessage());
      return null;
    }
  }
}
