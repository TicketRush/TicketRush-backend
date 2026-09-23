package com.ticketrush.boundedcontext.performance.out.repository;

import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.policy.ShowTimeCutoff;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.global.types.PerformanceStatus;
import org.springframework.data.domain.Slice;

public interface PerformanceRepositoryCustom {

  /**
   * 커서 기반 공연 목록 조회. size는 양수를 전제한다 — 정규화(기본값·상한 캡)는 웹 계층의 {@code CursorPageRequest} 컴팩트 생성자가 책임진다.
   *
   * <p>{@code cutoff} 이후에 시작하는 공연만 돌려준다 (#651). 필터와 무관하게 항상 적용되며, 기준 문장과 시간대 해석은 {@link
   * com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy}가 소유한다.
   */
  Slice<Performance> findByFilters(
      Genre genre,
      Long minPrice,
      Long maxPrice,
      PerformanceStatus status,
      Long cursorId,
      int size,
      ShowTimeCutoff cutoff);
}
