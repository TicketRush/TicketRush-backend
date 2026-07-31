package com.ticketrush.queue;

/**
 * 대기열 Redis 키(ADR 0009 §2).
 *
 * <p>키 컨벤션 {@code {도메인}:{엔티티}[:{식별자}]} (docs/backend-convention.md §4). ADR 0009 §2 본문은 {@code
 * waiting:{performanceId}} 로 적었지만, 전 서비스가 단일 Redis DB 0을 공유하고 논리 분리 수단이 prefix뿐이라 도메인 세그먼트 {@code
 * queue:} 를 붙인다. prefix를 리터럴로 흩뿌리지 않고 여기 한 곳에서만 관리하는 것도 같은 문서의 요구다(seat-service {@code SeatLockKey}
 * 선례).
 *
 * <p>prod Redis는 {@code maxmemory 64mb} + {@code noeviction} 이라 상한에 닿으면 좌석 락 SET까지 거절된다(ADR 0008).
 * 여기 정의된 모든 키는 TTL을 반드시 동반해야 한다 — 잔류가 곧 예매 장애다.
 */
public final class WaitingRoomKey {

  private static final String WAITING_PREFIX = "queue:waiting:";
  private static final String WAITING_TOKEN_PREFIX = "queue:waiting-token:";
  private static final String OPENED_AT_PREFIX = "queue:opened-at:";
  private static final String ENTRY_TOKEN_PREFIX = "queue:entry-token:";

  private WaitingRoomKey() {}

  /** 공연별 대기 순번 ZSET. member=userId, score=진입 epoch ms. */
  public static String waiting(Long performanceId) {
    return WAITING_PREFIX + performanceId;
  }

  /** 대기 토큰 → {@code {performanceId}:{userId}}. 상태 확인 경로가 JWT 대신 대조하는 값(ADR 0009 §4). */
  public static String waitingToken(String token) {
    return WAITING_TOKEN_PREFIX + token;
  }

  /** 공연별 대기열 개시 시각(epoch ms). 승급 임계치의 기준점. */
  public static String openedAt(Long performanceId) {
    return OPENED_AT_PREFIX + performanceId;
  }

  /** 입장 토큰 → userId. 예매 경로 게이트가 대조한다. */
  public static String entryToken(String token) {
    return ENTRY_TOKEN_PREFIX + token;
  }
}
