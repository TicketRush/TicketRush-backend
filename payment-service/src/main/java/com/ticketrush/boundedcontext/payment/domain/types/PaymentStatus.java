package com.ticketrush.boundedcontext.payment.domain.types;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PaymentStatus {
  PENDING("결제 대기"),
  COMPLETED("결제 완료"),
  CANCELED("결제 취소"),
  FAILED("결제 실패");

  private final String description;
}
