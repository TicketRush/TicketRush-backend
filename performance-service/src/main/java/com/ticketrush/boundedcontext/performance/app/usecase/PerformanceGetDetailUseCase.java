package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceDetailResponse;
import com.ticketrush.boundedcontext.performance.app.mapper.PerformanceMapper;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformanceGetDetailUseCase {

  private final PerformanceRepository performanceRepository;
  private final PerformanceMapper performanceMapper;

  @Transactional(readOnly = true)
  public PerformanceDetailResponse execute(Long performanceId) {
    return performanceRepository
        .findById(performanceId)
        .map(performanceMapper::toDetailResponse)
        .orElseThrow(() -> new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND));
  }
}
