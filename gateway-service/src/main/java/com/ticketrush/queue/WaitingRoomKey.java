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
  private static final String USER_TOKEN_PREFIX = "queue:user-token:";
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

  /**
   * 사용자별 대기 토큰. 재진입이 <b>키 개수 관점에서도</b> 멱등하게 만든다.
   *
   * <p>이게 없으면 진입 호출마다 새 UUID 키가 생겨 요청 수에 비례해 Redis가 찬다. {@code maxmemory 64mb} + {@code noeviction}
   * 이라 그 끝은 좌석 락 SET 거절 = 예매 전면 장애다(ADR 0008). 이 키가 있으면 상한이 요청 수가 아니라 <b>사용자 수</b>에 묶인다.
   */
  public static String userToken(Long performanceId, long userId) {
    return USER_TOKEN_PREFIX + performanceId + ":" + userId;
  }

  /**
   * 공연별 대기열 개시 시각(epoch ms). 승급 임계치의 기준점.
   *
   * <p><b>진입의 부작용으로 생기지 않는다.</b> 첫 진입자가 이 값을 정하면, 오픈 몇 시간 전에 한 번 진입해 둔 사람이 {@code (경과 × rate)} 를
   * 임의로 부풀려 오픈 시각에 전원을 즉시 통과시킬 수 있다 — 대기열이 통째로 무력화된다. 운영자만 {@code POST /api/v1/queue/{id}/open} 으로
   * 심는다.
   */
  public static String openedAt(Long performanceId) {
    return OPENED_AT_PREFIX + performanceId;
  }

  /** 입장 토큰 → userId. 예매 경로 게이트가 대조한다. */
  public static String entryToken(String token) {
    return ENTRY_TOKEN_PREFIX + token;
  }
}
