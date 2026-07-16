package com.ticketrush.boundedcontext.seat.domain.constant;

/**
 * 좌석 선점 Redisson 락의 Redis 키. prefix가 여러 곳(락 획득/해제, 만료 리스너)에 흩어져 하드코딩되던 것을 한 곳으로 모은다. 포맷: {@code
 * seat:lock:{seatId}} — 소문자 콜론 컨벤션(docs/backend-convention.md §4 Redis 키 컨벤션).
 */
public final class SeatLockKey {

  public static final String PREFIX = "seat:lock:";

  private SeatLockKey() {}

  public static String of(Long seatId) {
    return PREFIX + seatId;
  }
}
