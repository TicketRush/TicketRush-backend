package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.banner.app.usecase.BannerSyncUseCase;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformancePatchRequest;
import com.ticketrush.boundedcontext.performance.app.mapper.PerformanceMapper;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.constants.CacheConstants;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformancePatchUseCase {

  private final PerformanceRepository performanceRepository;
  private final PerformanceMapper performanceMapper;
  private final BannerSyncUseCase bannerSyncUseCase;

  @CacheEvict(cacheNames = CacheConstants.PERFORMANCE_LIST_CACHE, allEntries = true)
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
        request.address(),
        request.bookingOpenAt());

    /*
     * 캐릭터는 계약이 달라 빈 한마디를 삭제 신호로 사용한다(#650).
     * JSON 직렬화는 등록과 같은 Mapper 메서드를 사용한다.
     */
    performance.updateCharacter(
        performanceMapper.toJsonString(request.characterConfig()), request.characterMessage());

    /*
     * 배너 생성·소제목 수정·배너 삭제를 공연 수정과 같은 트랜잭션에서 처리한다.
     */
    bannerSyncUseCase.synchronizeForPatch(
        performanceId, request.displayOnBanner(), request.bannerSubtitle());
  }
}
