package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.constants.CacheConstants;
import com.ticketrush.global.types.PerformanceStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 예매 오픈 시각이 도래한 UPCOMING 공연을 ON_SALE로 벌크 전환한다 (#298).
 *
 * <p>비교용 시각(Asia/Seoul 벽시계, {@link PerformanceShowTimePolicy#bookingOpenCutoff()})과 기록용 시각(UTC,
 * auditing과 동일)을 따로 만든다 (#653). 예전에는 JVM 기본 존의 {@code now()} 하나를 양쪽에 썼는데, 운영 컨테이너가 UTC라 어드민이 KST로
 * 넣은 오픈 시각이 9시간 늦게 열렸다. 로컬(KST JVM)에서는 드러나지 않는 버그였다. 자세한 이유는 {@code
 * PerformanceRepository.bulkTransitionStatusByBookingOpenAtDue} 문서 참고.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceOpenBookingUseCase {

  private final PerformanceRepository performanceRepository;
  private final PerformanceShowTimePolicy showTimePolicy;
  private final CacheManager cacheManager;
  private final Clock clock;

  @Transactional
  public int execute() {
    int openedCount =
        performanceRepository.bulkTransitionStatusByBookingOpenAtDue(
            PerformanceStatus.UPCOMING,
            PerformanceStatus.ON_SALE,
            showTimePolicy.bookingOpenCutoff(),
            LocalDateTime.now(clock));

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
