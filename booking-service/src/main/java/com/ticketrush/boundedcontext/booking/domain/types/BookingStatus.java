package com.ticketrush.boundedcontext.booking.domain.types;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum BookingStatus {
  PENDING("결제 대기"), // 좌석 선점 후 결제창 진입
  CONFIRMED("예매 확정"), // 결제 완료
  CANCELED("예매 취소"), // 결제 전 취소
  REFUNDING("환불 진행 중"), // 결제 후 취소, PG사 환불 응답 대기 상태
  REFUNDED("환불 완료"), // PG사 환불 성공
  REFUND_FAILED("환불 실패"), // PG사 환불 최종 실패, 보상 트랜잭션으로 확정 (#91)
  EXPIRED("기한 만료"); // 결제 대기 시간 초과로 인한 자동 취소

  private final String description;
}
