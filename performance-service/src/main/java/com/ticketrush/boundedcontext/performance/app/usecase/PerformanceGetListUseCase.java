package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.mapper.PerformanceMapper;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
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
  public Page<PerformanceListResponse> execute(Genre genre, Pageable pageable) {
    Page<Performance> performances =
        (genre != null)
            ? performanceRepository.findByGenre(genre, pageable)
            : performanceRepository.findAll(pageable);

    return performances.map(performanceMapper::toListResponse);
  }
}
