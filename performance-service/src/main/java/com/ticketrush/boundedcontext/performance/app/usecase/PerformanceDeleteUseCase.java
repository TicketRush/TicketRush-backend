package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
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
public class PerformanceDeleteUseCase {

  private final PerformanceRepository performanceRepository;

  /** 삭제 시각을 auditing 과 같은 축으로 만든다 (#671). common {@code ClockConfig}는 UTC 다. */
  private final Clock clock;

  @CacheEvict(cacheNames = CacheConstants.PERFORMANCE_LIST_CACHE, allEntries = true)
  @Transactional
  public void execute(Long performanceId) {
    Performance performance =
        performanceRepository
            .findById(performanceId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND));

    // TODO: #87 seat-service 연동 후 SOLD 좌석 존재 시 PERFORMANCE_HAS_SOLD_SEATS 예외 발생 필요
    performance.softDelete(LocalDateTime.now(clock));
  }
}
