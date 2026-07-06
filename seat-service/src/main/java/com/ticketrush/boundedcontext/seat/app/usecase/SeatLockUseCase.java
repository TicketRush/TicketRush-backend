package com.ticketrush.boundedcontext.seat.app.usecase;

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

  private static final String SEAT_LOCK_PREFIX = "seat:lock:";
  private static final int LOCK_TTL_MINUTES = 5;

  public Optional<LocalDateTime> execute(Long seatId, Long userId) {
    String lockKey = SEAT_LOCK_PREFIX + seatId;
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
