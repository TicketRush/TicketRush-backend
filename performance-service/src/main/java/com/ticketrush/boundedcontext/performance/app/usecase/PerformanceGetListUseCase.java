package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListSlice;
import com.ticketrush.boundedcontext.performance.app.mapper.PerformanceMapper;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.constants.CacheConstants;
import com.ticketrush.global.dto.request.CursorPageRequest;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformanceGetListUseCase {

  private final PerformanceRepository performanceRepository;
  private final PerformanceMapper performanceMapper;

  /**
   * 캐싱 대상은 메인 화면 트래픽이 집중되는 <b>무필터 + 첫 페이지</b> 조합만으로 제한한다 — minPrice/maxPrice/cursorId가 자유값이라 전 조합
   * 캐싱은 키 카디널리티가 무한하기 때문(키는 size별 최대 {@code MAX_PAGE_SIZE}개).
   */
  @Cacheable(
      cacheNames = CacheConstants.PERFORMANCE_LIST_CACHE,
      key = "'size=' + #pageRequest.size()",
      condition =
          "#genre == null && #minPrice == null && #maxPrice == null && #status == null"
              + " && #pageRequest.cursorId() == null")
  @Transactional(readOnly = true)
  public PerformanceListSlice execute(
      Genre genre,
      Long minPrice,
      Long maxPrice,
      PerformanceStatus status,
      CursorPageRequest pageRequest) {

    validatePriceRange(minPrice, maxPrice);

    return PerformanceListSlice.from(
        performanceRepository
            .findByFilters(
                genre, minPrice, maxPrice, status, pageRequest.cursorId(), pageRequest.size())
            .map(performanceMapper::toListResponse));
  }

  private void validatePriceRange(Long minPrice, Long maxPrice) {
    if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_INVALID_PRICE_RANGE);
    }
  }
}
