package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.domain.constant.SeatLockKey;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.global.constants.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatLockUseCase {

  private final RedissonClient redissonClient;
  private final MeterRegistry meterRegistry;

  // 선점 유지 시간의 SSOT는 도메인이다(#562). 관리자 좌석 상세가 holdExpiredAt - TTL로 선점 시작 시각을 유도하므로,
  // 여기에 사본을 두면 두 값이 갈라진 순간 화면의 "예약 시작 시간"이 조용히 틀어진다.
  private static final int LOCK_TTL_MINUTES = Seat.HOLD_TTL_MINUTES;

  public Optional<LocalDateTime> execute(Long seatId, Long userId) {
    String lockKey = SeatLockKey.of(seatId);
    RLock lock = redissonClient.getLock(lockKey);

    try {
      // 락 획득 시도
      boolean isLocked = lock.tryLock(0, LOCK_TTL_MINUTES, TimeUnit.MINUTES);

      if (isLocked) {
        // 락 획득 성공 시 만료 시간 반환
        return Optional.of(LocalDateTime.now().plusMinutes(LOCK_TTL_MINUTES));
      }

      // 락 획득 실패 (이미 다른 사용자가 선점 중, 락 경합)
      Counter.builder(MetricNames.SEAT_LOCK_CONTENTION).register(meterRegistry).increment();
    } catch (InterruptedException e) {
      log.error("Redisson 락 획득 중 인터럽트 발생. seatId: {}", seatId, e);
      Thread.currentThread().interrupt(); // 인터럽트 상태 복구
    }

    // 락 획득 실패
    return Optional.empty();
  }
}
