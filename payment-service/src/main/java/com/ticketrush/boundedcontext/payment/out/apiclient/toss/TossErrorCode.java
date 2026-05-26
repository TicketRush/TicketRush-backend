package com.ticketrush.boundedcontext.payment.out.apiclient.toss;

import com.ticketrush.global.status.ErrorStatus;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Toss Payments 거절 코드 → 내부 {@link ErrorStatus} 매핑.
 *
 * <p>enum 이름은 Toss 응답 {@code code} 값의 prefix와 일치시킨다. {@link #from(String)} 은 가장 긴 prefix와 매칭되는 항목을
 * 반환하며, 어떤 항목과도 매칭되지 않으면 {@link #UNKNOWN}으로 폴백한다.
 *
 * <p>참고: https://docs.tosspayments.com/reference/error-codes
 */
public enum TossErrorCode {
  ALREADY_PROCESSED_PAYMENT(ErrorStatus.PAYMENT_ALREADY_COMPLETED),
  NOT_FOUND_PAYMENT_SESSION(ErrorStatus.PAYMENT_SESSION_NOT_FOUND),
  INVALID_CARD(ErrorStatus.PAYMENT_CARD_REJECTED),
  REJECT_CARD(ErrorStatus.PAYMENT_CARD_REJECTED),
  EXCEED_MAX_DAILY_PAYMENT(ErrorStatus.PAYMENT_LIMIT_EXCEEDED),
  EXCEED_MAX_AMOUNT(ErrorStatus.PAYMENT_LIMIT_EXCEEDED),
  FORBIDDEN_REQUEST(ErrorStatus.PAYMENT_PG_AUTH_FAILED),
  UNAUTHORIZED_KEY(ErrorStatus.PAYMENT_PG_AUTH_FAILED),
  UNKNOWN(ErrorStatus.PAYMENT_APPROVAL_FAILED);

  private final ErrorStatus errorStatus;

  TossErrorCode(ErrorStatus errorStatus) {
    this.errorStatus = errorStatus;
  }

  public ErrorStatus getErrorStatus() {
    return errorStatus;
  }

  public static TossErrorCode from(String code) {
    if (code == null || code.isBlank()) {
      return UNKNOWN;
    }
    return Arrays.stream(values())
        .filter(value -> value != UNKNOWN)
        .filter(value -> code.startsWith(value.name()))
        .max(Comparator.comparingInt(value -> value.name().length()))
        .orElse(UNKNOWN);
  }
}
