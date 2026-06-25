package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Toss Payments 결제 취소 응답 (필요 필드만 매핑).
 *
 * <p>취소 1건당 {@link Cancel} 한 항목이 {@code cancels} 배열에 누적된다. 전액 환불 시 마지막 항목이 이번 취소 내역이다.
 *
 * <p>전체 응답 명세: https://docs.tosspayments.com/reference#payment-객체
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossCancelResponse(String paymentKey, String status, List<Cancel> cancels) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Cancel(String transactionKey, Long cancelAmount, OffsetDateTime canceledAt) {}
}
