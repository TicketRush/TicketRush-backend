package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceOpenBookingUseCase {

  private final PerformanceRepository performanceRepository;

  @Transactional
  public int execute() {
    int openedCount =
        performanceRepository.bulkTransitionStatusByBookingOpenAtDue(
            PerformanceStatus.UPCOMING, PerformanceStatus.ON_SALE, LocalDateTime.now());

    if (openedCount > 0) {
      log.info("예매 오픈 시각 도래 공연 {}건을 ON_SALE 상태로 전환했습니다.", openedCount);
    }
    return openedCount;
  }
}
