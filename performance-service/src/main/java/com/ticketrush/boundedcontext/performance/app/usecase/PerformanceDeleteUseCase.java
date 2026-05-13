package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformanceDeleteUseCase {

  private final PerformanceRepository performanceRepository;

  @Transactional
  public void execute(Long performanceId) {
    Performance performance =
        performanceRepository
            .findById(performanceId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND));

    // TODO: #87 seat-service 연동 후 SOLD 좌석 존재 시 PERFORMANCE_HAS_SOLD_SEATS 예외 발생 필요
    performance.softDelete();
  }
}
