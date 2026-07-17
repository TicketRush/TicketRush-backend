package com.ticketrush.global.event;

public interface DomainEvent {
  // 1. 라우팅 정보 (Kafka 전송 시 사용)
  String topic();

  String key();

  // 2. 메타데이터 (로깅, 추적, Outbox 저장 시 사용)
  String eventName();

  String traceId();

  /**
   * 이벤트를 발생시킨 애그리거트의 식별자.
   *
   * <p>파티션 키({@link #key()})와 별개 개념이다. key()는 라우팅(파티셔닝) 목적이라 도메인 간 연관 키(예: bookingId)를 쓸 수 있는 반면,
   * aggregateId는 이 이벤트를 낳은 애그리거트 자신의 PK(예: Payment 이벤트라면 paymentId)를 가리킨다. Outbox 저장 시
   * aggregateType과 짝을 이뤄 애그리거트 단위 추적/조회에 사용한다.
   */
  String aggregateId();
}
