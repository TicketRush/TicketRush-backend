package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformancePatchRequest;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformancePatchUseCase {

  private final PerformanceRepository performanceRepository;

  @Transactional
  public void execute(Long performanceId, PerformancePatchRequest request) {
    Performance performance =
        performanceRepository
            .findById(performanceId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND));

    performance.update(
        request.title(),
        request.performer(),
        request.genre(),
        request.description(),
        request.showDate(),
        request.showTime(),
        request.durationMinutes(),
        request.price(),
        request.totalSeats(),
        request.address());
  }
}
