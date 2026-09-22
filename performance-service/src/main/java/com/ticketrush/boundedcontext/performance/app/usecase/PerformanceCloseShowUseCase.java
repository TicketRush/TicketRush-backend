package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy;
import com.ticketrush.boundedcontext.performance.domain.policy.ShowTimeCutoff;
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
 * 공연 시작 시각이 지난 ON_SALE 공연을 CLOSED로 벌크 전환한다 (#651).
 *
 * <p>이전에는 CLOSED가 어드민 수동 전환으로만 생겨, 공연이 끝나도 상태는 ON_SALE로 남았다. 사용자 목록은 같은 이슈에서 시작 시각 조건으로 지난 공연을
 * 걸러내므로 이 전환은 노출을 막는 수단이 아니라 <b>상태를 사실과 맞추는</b> 수단이다. 그래서 오픈 스케줄러(10초)처럼 급할 이유가 없다.
 *
 * <p>{@link PerformanceOpenBookingUseCase}와 같은 구조다 — 벌크 UPDATE 한 문장, 전환 건수가 있을 때만 커밋 이후에 목록 캐시를
 * 비우고, 무효화 실패는 삼킨다. 캐시 무효화 코드를 공용으로 뽑지 않고 복제한 이유는 사용처가 둘뿐이고, 뽑으려면 오픈 유스케이스를 함께 고쳐야 하기 때문이다. 셋째 사용처가
 * 생기면 그때 공용화한다.
 *
 * <p>비교용 시각(Asia/Seoul 벽시계)과 기록용 시각(UTC, auditing과 동일)을 따로 만든다. 자세한 이유는 {@code
 * PerformanceRepository.bulkTransitionStatusByShowTimePassed} 문서 참고.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceCloseShowUseCase {

  private final PerformanceRepository performanceRepository;
  private final PerformanceShowTimePolicy showTimePolicy;
  private final CacheManager cacheManager;
  private final Clock clock;

  @Transactional
  public int execute() {
    ShowTimeCutoff cutoff = showTimePolicy.cutoff();

    int closedCount =
        performanceRepository.bulkTransitionStatusByShowTimePassed(
            PerformanceStatus.ON_SALE,
            PerformanceStatus.CLOSED,
            cutoff.date(),
            cutoff.time(),
            LocalDateTime.now(clock));

    if (closedCount > 0) {
      log.info("공연 시작 시각이 지난 공연 {}건을 CLOSED 상태로 전환했습니다.", closedCount);
      registerCacheEvictionAfterCommit();
    }
    return closedCount;
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
