package com.ticketrush.boundedcontext.booking.domain.policy;

import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * REFUNDING 고착 판정 기준을 소유한다 (#397).
 *
 * <p>고착 식별(관리자 조회)과 고착 복구(환불 재시도 가드)는 <b>반드시 같은 임계</b>를 봐야 한다. 어긋나면 "목록에는 보이는데 재시도는 거절되는" 비정합이
 * 생기므로, 임계와 {@link #cutoff()} 계산을 이 한 곳에 둔다.
 *
 * <p>임계는 Kafka 재시도 소진(약 31초)보다 훨씬 길게 잡아 컨슈머 랙·재배포 중 일시 지연을 고착으로 오탐하지 않게 한다.
 */
@Component
public class RefundingStuckPolicy {

  private final Clock clock;
  private final long thresholdMinutes;

  public RefundingStuckPolicy(
      Clock clock,
      @Value("${app.booking.refunding-stuck-threshold-minutes:30}") long thresholdMinutes) {
    this.clock = clock;
    this.thresholdMinutes = thresholdMinutes;
  }

  /** REFUNDING 진입 시각({@code updatedAt})이 이 시각보다 이전이면 고착이다. */
  public LocalDateTime cutoff() {
    return LocalDateTime.now(clock).minusMinutes(thresholdMinutes);
  }
}
