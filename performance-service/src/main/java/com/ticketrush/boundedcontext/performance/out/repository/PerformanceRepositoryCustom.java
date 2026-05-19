package com.ticketrush.boundedcontext.performance.out.repository;

import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PerformanceRepositoryCustom {

  Page<Performance> findByFilters(
      Genre genre, Long minPrice, Long maxPrice, PerformanceStatus status, Pageable pageable);
}
