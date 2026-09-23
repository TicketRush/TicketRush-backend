package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.banner.app.usecase.BannerSyncUseCase;
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
  private final BannerSyncUseCase bannerSyncUseCase;

  /** 삭제 시각을 auditing과 같은 축으로 만든다 (#671). common {@code ClockConfig}는 UTC다. */
  private final Clock clock;

  @CacheEvict(cacheNames = CacheConstants.PERFORMANCE_LIST_CACHE, allEntries = true)
  @Transactional
  public void execute(Long performanceId) {
    Performance performance =
        performanceRepository
            .findById(performanceId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND));

    // TODO: #87 seat-service 연동 후 SOLD 좌석 존재 시
    // PERFORMANCE_HAS_SOLD_SEATS 예외 발생 필요

    /*
     * 배너를 먼저 삭제하고 뒤 배너의 순서를 재정렬한다.
     * 공연 소프트 삭제와 같은 트랜잭션이므로 어느 한쪽이 실패하면 모두 롤백된다.
     */
    bannerSyncUseCase.removeForPerformance(performanceId);

    performance.softDelete(LocalDateTime.now(clock));
  }
}
