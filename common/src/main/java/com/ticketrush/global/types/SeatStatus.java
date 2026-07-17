package com.ticketrush.global.types;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SeatStatus {
  AVAILABLE("예매 가능"),
  HOLD("임시 예매"), // 사용자가 선택해서 5분간 임시로 선점된 상태
  SOLD("예매 완료"); // 결제가 완전히 끝나서 확정된 상태

  private final String description;
}
