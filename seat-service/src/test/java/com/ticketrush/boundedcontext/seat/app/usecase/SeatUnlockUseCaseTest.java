package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

@ExtendWith(MockitoExtension.class)
class SeatUnlockUseCaseTest {

  @InjectMocks private SeatUnlockUseCase seatUnlockUseCase;

  @Mock private RedissonClient redissonClient;
  @Mock private RLock redissonLock;

  @Test
  @DisplayName("성공: 현재 락이 걸려있고, 호출한 스레드가 락의 주인이면 정상적으로 해제한다")
  void execute_success_unlock() {
    // given
    given(redissonClient.getLock(anyString())).willReturn(redissonLock);
    given(redissonLock.isLocked()).willReturn(true);
    given(redissonLock.isHeldByCurrentThread()).willReturn(true);

    // when
    seatUnlockUseCase.execute(100L);

    // then
    verify(redissonLock).unlock();
  }

  @Test
  @DisplayName("스킵: 락이 이미 해제되었거나 만료된 상태면 unlock()을 호출하지 않는다")
  void execute_skip_when_not_locked() {
    // given
    given(redissonClient.getLock(anyString())).willReturn(redissonLock);
    given(redissonLock.isLocked()).willReturn(false);

    // when
    seatUnlockUseCase.execute(100L);

    // then
    verify(redissonLock, never()).unlock();
  }

  @Test
  @DisplayName("스킵: 락이 걸려있지만 현재 스레드가 주인이 아니면 타인의 락을 빼앗지 않는다(unlock 미호출)")
  void execute_skip_when_not_held_by_current_thread() {
    // given
    given(redissonClient.getLock(anyString())).willReturn(redissonLock);
    given(redissonLock.isLocked()).willReturn(true);
    given(redissonLock.isHeldByCurrentThread()).willReturn(false); // 내가 건 락이 아님

    // when
    seatUnlockUseCase.execute(100L);

    // then
    verify(redissonLock, never()).unlock();
  }

  @Test
  @DisplayName("성공: 좌석 락을 소유 스레드와 무관하게 강제 해제한다")
  void forceRelease_success() {
    // given
    given(redissonClient.getLock(anyString())).willReturn(redissonLock);
    given(redissonLock.forceUnlock()).willReturn(true);

    // when
    seatUnlockUseCase.forceRelease(100L);

    // then
    verify(redissonLock).forceUnlock();
  }
}
