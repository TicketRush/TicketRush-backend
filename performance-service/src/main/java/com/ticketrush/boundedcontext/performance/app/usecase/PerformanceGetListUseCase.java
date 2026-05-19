package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.mapper.PerformanceMapper;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformanceGetListUseCase {

  private final PerformanceRepository performanceRepository;
  private final PerformanceMapper performanceMapper;

  @Transactional(readOnly = true)
  public Page<PerformanceListResponse> execute(
      Genre genre, Long minPrice, Long maxPrice, PerformanceStatus status, Pageable pageable) {

    validatePriceRange(minPrice, maxPrice);

    return performanceRepository
        .findByFilters(genre, minPrice, maxPrice, status, pageable)
        .map(performanceMapper::toListResponse);
  }

  private void validatePriceRange(Long minPrice, Long maxPrice) {
    if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_INVALID_PRICE_RANGE);
    }
  }
}
