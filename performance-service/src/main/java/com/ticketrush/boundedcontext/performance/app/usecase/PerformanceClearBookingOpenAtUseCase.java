package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.constants.CacheConstants;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformanceClearBookingOpenAtUseCase {

  private final PerformanceRepository performanceRepository;
  private final Clock clock;

  /**
   * 예매 오픈 시각을 해제한다.
   *
   * <p>공연 상태는 변경하지 않으므로 이미 ON_SALE로 전환된 공연에 소급 효과는 없다. 이미 해제된 공연에 다시 요청해도 예외 없이 통과한다(멱등).
   *
   * <p>{@code updatedAt}은 auditing과 같은 UTC Clock 값으로 기록한다 (#653). 벌크 JPQL에는 auditing이 적용되지 않아 직접
   * 넘기는데, JVM 기본 존의 {@code now()}를 쓰면 로컬(KST JVM)에서 auditing 값과 한 테이블에 섞인다. 비교에는 쓰이지 않으므로 시간대 오판과는
   * 무관하다.
   */
  @CacheEvict(cacheNames = CacheConstants.PERFORMANCE_LIST_CACHE, allEntries = true)
  @Transactional
  public void execute(Long performanceId) {
    int updated = performanceRepository.clearBookingOpenAt(performanceId, LocalDateTime.now(clock));

    if (updated == 0) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND);
    }
  }
}
