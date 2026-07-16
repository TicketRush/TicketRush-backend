package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.constants.CacheConstants;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceOpenBookingUseCase {

  private final PerformanceRepository performanceRepository;
  private final CacheManager cacheManager;

  @Transactional
  public int execute() {
    int openedCount =
        performanceRepository.bulkTransitionStatusByBookingOpenAtDue(
            PerformanceStatus.UPCOMING, PerformanceStatus.ON_SALE, LocalDateTime.now());

    if (openedCount > 0) {
      log.info("예매 오픈 시각 도래 공연 {}건을 ON_SALE 상태로 전환했습니다.", openedCount);
      registerCacheEvictionAfterCommit();
    }
    return openedCount;
  }

  /**
   * 상태 전환은 목록 응답 필드이자 필터 조건인 status를 바꾸므로 캐시를 무효화한다. {@code @CacheEvict}는 결과값(전환 건수) 조건을 걸 수 없어 직접
   * 호출하되, 커밋 전에 비우면 다른 요청이 커밋 전 스냅샷을 재적재할 수 있어 <b>커밋 이후</b>로 미룬다.
   */
  private void registerCacheEvictionAfterCommit() {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            evictPerformanceListCache();
          }
        });
  }

  /** 무효화 실패가 스케줄러를 죽이지 않도록 예외를 삼킨다(TTL 안전망으로 정리됨). */
  private void evictPerformanceListCache() {
    try {
      Cache cache = cacheManager.getCache(CacheConstants.PERFORMANCE_LIST_CACHE);
      if (cache != null) {
        cache.clear();
      }
    } catch (RuntimeException e) {
      log.warn("공연 목록 캐시 무효화 실패 — TTL 만료로 정리됩니다.", e);
    }
  }
}
