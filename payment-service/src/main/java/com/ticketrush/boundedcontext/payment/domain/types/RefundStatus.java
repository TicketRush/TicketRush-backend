package com.ticketrush.boundedcontext.payment.domain.types;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RefundStatus {
  PENDING("환불 대기"),
  COMPLETED("환불 완료"),
  FAILED("환불 실패");

  private final String description;
}
