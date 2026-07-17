package com.ticketrush.boundedcontext.seat.app.usecase;

import com.ticketrush.boundedcontext.seat.domain.constant.SeatLockKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

// @Transactional 제거 (Redis 작업이므로 DB 트랜잭션 불필요)
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatUnlockUseCase {

  private final RedissonClient redissonClient;

  public void execute(Long seatId) {
    String lockKey = SeatLockKey.of(seatId);
    RLock lock = redissonClient.getLock(lockKey);

    /*
     * 안전한 락 해제를 위한 2단계 검증
     * 1. isLocked(): 현재 락이 걸려 있는 상태인지 확인
     * 2. isHeldByCurrentThread(): 락을 획득한 스레드와 현재 해제를 요청한 스레드가 일치하는지 확인
     */
    if (lock.isLocked() && lock.isHeldByCurrentThread()) {
      lock.unlock();
      log.debug("Redisson 락 정상 해제 완료. seatId: {}", seatId);
    } else {
      // 이미 TTL이 지나서 풀렸거나, 다른 스레드(사용자)가 락을 획득한 상태이므로 무시합니다.
      log.warn("락 해제 스킵: 락을 소유하고 있지 않거나 이미 자동 만료되었습니다. seatId: {}", seatId);
    }
  }

  public void forceRelease(Long seatId) {
    String lockKey = SeatLockKey.of(seatId);
    RLock lock = redissonClient.getLock(lockKey);

    if (lock.forceUnlock()) {
      log.debug("Redisson 락 강제 해제 완료. seatId: {}", seatId);
    }
  }
}
