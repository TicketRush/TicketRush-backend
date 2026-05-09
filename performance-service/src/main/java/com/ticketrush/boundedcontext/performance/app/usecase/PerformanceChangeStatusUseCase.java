package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceChangeStatusRequest;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformanceChangeStatusUseCase {

  private final PerformanceRepository performanceRepository;

  @Transactional
  public void execute(Long performanceId, PerformanceChangeStatusRequest request) {
    Performance performance =
        performanceRepository
            .findById(performanceId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND));

    PerformanceStatus targetStatus = request.status();

    if (!performance.canTransitionTo(targetStatus)) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_INVALID_STATUS_TRANSITION);
    }

    performance.changeStatus(targetStatus);
  }
}
