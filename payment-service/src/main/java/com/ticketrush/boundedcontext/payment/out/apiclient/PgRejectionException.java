package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.Getter;

/**
 * PG(예: Toss)가 4xx로 내려준 결제 거절을 나타내는 예외(#332).
 *
 * <p>매핑된 내부 {@link ErrorStatus}와 함께 PG가 준 <b>원본</b> 거절 코드/사유를 실어, {@code
 * PaymentConfirmUseCase.recordFailedPayment}가 FAILED 이력에 원본을 저장할 수 있게 한다. 원본 추적은 내부용이므로 상위 {@code
 * BusinessException.data}는 채우지 않는다({@code super(errorStatus)} → data=null). 그러면 {@code
 * GlobalExceptionHandler}가 data 미설정 경로로 흘러 원본 PG 코드가 HTTP 응답 body에 노출되지 않는다.
 *
 * <p>{@code rawCode}/{@code rawMessage}는 에러 응답 body 파싱 실패 시 null일 수 있다.
 */
@Getter
public class PgRejectionException extends BusinessException {

  private final String rawCode;
  private final String rawMessage;

  public PgRejectionException(ErrorStatus errorStatus, String rawCode, String rawMessage) {
    super(errorStatus);
    this.rawCode = rawCode;
    this.rawMessage = rawMessage;
  }
}
