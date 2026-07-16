package com.ticketrush.boundedcontext.performance.out.repository;

import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import org.springframework.data.domain.Slice;

public interface PerformanceRepositoryCustom {

  /**
   * 커서 기반 공연 목록 조회. size는 양수를 전제한다 — 정규화(기본값·상한 캡)는 웹 계층의 {@code CursorPageRequest} 컴팩트 생성자가 책임진다.
   */
  Slice<Performance> findByFilters(
      Genre genre, Long minPrice, Long maxPrice, PerformanceStatus status, Long cursorId, int size);
}
