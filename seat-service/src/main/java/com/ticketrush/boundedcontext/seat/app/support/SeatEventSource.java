package com.ticketrush.boundedcontext.seat.app.support;

/**
 * 좌석 상태 이벤트를 만든 발행 경로(#520). {@code MetricNames.TAG_SOURCE} 태그 값이 된다.
 *
 * <p><b>왜 필요한가.</b> #403 실측에서 구독자 600명 구간의 전파 지연 p95가 2.77s인데 자기 이벤트의 팬아웃 몫은 약 170ms뿐이었다. 나머지는 앞에
 * 쌓인 큐를 기다린 시간이고 그게 지연의 지배항이다. 그런데 평균 이용률이 38%로 여유로운데도 큐 깊이가 평균 50, 최대 307까지 튄다 — 도착이 고르지 않다는 뜻인데
 * <b>그 불균일이 어느 경로에서 오는지 가를 수단이 없었다</b>(리포트 §10). 경로별 발행률을 {@code executor_queued_tasks}와 겹쳐 읽는 것이 그
 * 답이다.
 *
 * <p><b>왜 열거형인가.</b> 발행 경로는 호출자만 안다 — 좌석 상태로 유도할 수 없다. {@link #EXPIRE_SINGLE}과 {@link
 * #SCHEDULER_FALLBACK}은 결과 상태가 똑같이 {@code AVAILABLE}이다. 그리고 태그 값은 유한 집합이어야 하는데( {@code MetricNames}
 * 규약), 문자열 상수는 오타를 컴파일 시점에 못 잡는다.
 *
 * <p><b>새 발행 경로를 추가하면 여기에도 값을 더한다.</b> 빠뜨리면 그 도착이 다른 경로에 섞여 §10의 질문에 다시 답하지 못하게 된다.
 */
public enum SeatEventSource {

  /** 예매 유입 — Kafka {@code BookingCreatedEvent} → HOLD 진입 ({@code SeatHoldUseCase}). */
  BOOKING_HOLD("booking_hold"),

  /**
   * 해제 주 경로 — Redis 키 만료 이벤트로 1건씩 즉시 해제({@code SeatReleaseSingleUseCase}). 좌석 락 TTL이 5분이라 예매 5분 뒤 이
   * 경로가 두 번째 이벤트를 낸다. 고르게 퍼진다.
   */
  EXPIRE_SINGLE("expire_single"),

  /**
   * 해제 fallback — {@code SeatStatusScheduler}(60초)가 놓친 것을 쓸어 담는다({@code
   * SeatReleaseExpiredChunkProcessor}). tick당 최대 2,000건(chunk 25 × max-chunks 80)이 한 번에 executor로
   * 들어가는데 <b>큐 용량이 1000</b>이라, 버스트의 유력한 용의자다.
   */
  SCHEDULER_FALLBACK("scheduler_fallback"),

  /** 판매 확정 — 내부 API {@code SeatInternalController} → {@code SeatConfirmSoldUseCase}. */
  CONFIRM_SOLD("confirm_sold"),

  /**
   * 환불 반환 — Kafka {@code PaymentCanceledEvent} → SOLD를 AVAILABLE로({@code
   * SeatReleaseSoldSeatUseCase}).
   */
  REFUND_RELEASE("refund_release"),

  /**
   * 즉시 취소 반환 — 내부 API {@code SeatInternalController} → {@code SeatReleaseHoldUseCase} (#559). 사용자가
   * PENDING 예매를 직접 취소해 <b>만료 전</b> HOLD를 되돌린 경우다. {@link #EXPIRE_SINGLE}과 결과 상태는 같지만 도착 분포가 다르다 —
   * 만료는 예매 5분 뒤에 고르게 퍼지는 반면 이것은 사용자 행동 시점에 몰린다.
   */
  CANCEL_RELEASE("cancel_release"),

  /**
   * 관리자 강제 해제 — {@code SeatAdminController} → {@code SeatAdminForceReleaseHoldUseCase} (#562). 관리자가
   * 만료 전 HOLD를 직접 되돌린 경우다. 결과 상태는 {@link #EXPIRE_SINGLE}·{@link #CANCEL_RELEASE}와 같지만 도착이 극히 드물고
   * 사람의 조작에만 붙으므로, 이 태그의 발행률이 0이 아니라는 것 자체가 관리자 개입이 있었다는 신호다.
   */
  ADMIN_FORCE_RELEASE("admin_force_release");

  private final String tagValue;

  SeatEventSource(String tagValue) {
    this.tagValue = tagValue;
  }

  public String tagValue() {
    return tagValue;
  }
}
