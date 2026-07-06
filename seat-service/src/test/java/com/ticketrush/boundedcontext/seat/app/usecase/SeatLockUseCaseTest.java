package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.constants.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

@ExtendWith(MockitoExtension.class)
class SeatLockUseCaseTest {

  private SeatLockUseCase seatLockUseCase;

  @Mock private RedissonClient redissonClient;
  @Mock private RLock redissonLock;

  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    seatLockUseCase = new SeatLockUseCase(redissonClient, meterRegistry);
  }

  @Test
  @DisplayName("성공: Redisson 락 획득에 성공하면 만료 시간이 담긴 Optional을 반환한다")
  void execute_success_when_lock_acquired() throws InterruptedException {
    // given
    Long seatId = 100L;
    Long userId = 1L;
    String expectedKey = "seat:lock:" + seatId;

    given(redissonClient.getLock(expectedKey)).willReturn(redissonLock);
    // tryLock(waitTime, leaseTime, TimeUnit) 성공(true) 시뮬레이션
    given(redissonLock.tryLock(0, 5, TimeUnit.MINUTES)).willReturn(true);

    // when
    Optional<LocalDateTime> result = seatLockUseCase.execute(seatId, userId);

    // then
    assertThat(result).isPresent();
    assertThat(result.get()).isAfter(LocalDateTime.now().plusMinutes(4)); // 약 5분 뒤인지 확인
    verify(redissonClient).getLock(expectedKey);
  }

  @Test
  @DisplayName("실패: 이미 다른 사용자가 락을 점유 중이면 빈 Optional을 반환한다")
  void execute_fail_when_lock_already_exists() throws InterruptedException {
    // given
    Long seatId = 100L;
    Long userId = 1L;

    given(redissonClient.getLock(anyString())).willReturn(redissonLock);
    // 락 획득 실패(false) 시뮬레이션
    given(redissonLock.tryLock(0, 5, TimeUnit.MINUTES)).willReturn(false);

    // when
    Optional<LocalDateTime> result = seatLockUseCase.execute(seatId, userId);

    // then
    assertThat(result).isEmpty();
    assertThat(meterRegistry.counter(MetricNames.SEAT_LOCK_CONTENTION).count()).isEqualTo(1.0);
  }
}
