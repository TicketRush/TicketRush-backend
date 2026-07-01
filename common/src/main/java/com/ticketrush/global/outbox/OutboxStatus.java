package com.ticketrush.global.outbox;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OutboxStatus {
  PENDING("발행 대기"), // Outbox에 저장됐으나 아직 Kafka로 발행되지 않은 상태 (폴링 대상)
  SENT("발행 완료"), // 폴링 스케줄러가 Kafka로 발행을 완료한 상태
  FAILED("발행 실패"); // 발행에 실패한 상태 (재시도 대상)

  private final String description;
}
