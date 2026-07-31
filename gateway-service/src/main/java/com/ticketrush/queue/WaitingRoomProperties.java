package com.ticketrush.queue;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 대기열 설정(ADR 0009).
 *
 * <p>{@code enabled} 는 롤백 수단이자 계약 보호다. 게이트를 켜는 순간 {@code POST /api/v1/booking} 이 입장 토큰 없는 기존 클라이언트에
 * 전부 403이 되므로 <b>기본값은 false</b>이고, 측정 회차에서만 {@code QUEUE_ENABLED=true} 로 켠다. 되돌리기는 재배포·DDL 없이 환경변수
 * 한 줄 + 컨테이너 재시작이다.
 */
@ConfigurationProperties(prefix = "queue")
public record WaitingRoomProperties(
    boolean enabled,
    int admitRatePerSecond,
    int statusRpsCapacity,
    int minPollSeconds,
    int maxPollSeconds,
    Duration waitingTtl,
    Duration entryTokenTtl,
    Duration waitingCountCacheTtl) {

  public WaitingRoomProperties {
    if (admitRatePerSecond <= 0) {
      throw new IllegalArgumentException("queue.admit-rate-per-second는 1 이상이어야 합니다.");
    }
    if (statusRpsCapacity <= 0) {
      throw new IllegalArgumentException("queue.status-rps-capacity는 1 이상이어야 합니다.");
    }
    if (minPollSeconds <= 0 || maxPollSeconds < minPollSeconds) {
      throw new IllegalArgumentException("queue.min-poll-seconds ≤ queue.max-poll-seconds 여야 합니다.");
    }
    // TTL 누락은 noeviction Redis에서 좌석 락 SET 거절로 번진다(ADR 0008). 기본값으로 조용히 넘기지 않는다.
    if (waitingTtl == null || entryTokenTtl == null || waitingCountCacheTtl == null) {
      throw new IllegalArgumentException("queue.*-ttl은 전부 설정되어야 합니다.");
    }
  }
}
