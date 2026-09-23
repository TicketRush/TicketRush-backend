package com.ticketrush.boundedcontext.banner.app.usecase;

import com.ticketrush.boundedcontext.banner.app.dto.response.BannerResponse;
import com.ticketrush.boundedcontext.banner.app.mapper.BannerMapper;
import com.ticketrush.boundedcontext.banner.domain.entity.Banner;
import com.ticketrush.boundedcontext.banner.out.repository.BannerRepository;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BannerGetListUseCase {

  private final BannerRepository bannerRepository;
  private final PerformanceRepository performanceRepository;
  private final BannerMapper bannerMapper;

  /**
   * 배너를 노출 순서대로 조회하고 연결된 공연 정보를 조합하여 반환한다.
   *
   * <p>연결된 공연이 존재하지 않거나 삭제된 경우 해당 배너는 응답에서 제외한다.
   */
  @Transactional(readOnly = true)
  public List<BannerResponse> execute() {
    List<Banner> banners = bannerRepository.findAllByOrderByDisplayOrderAscIdAsc();

    if (banners.isEmpty()) {
      return List.of();
    }

    List<Long> performanceIds = banners.stream().map(Banner::getPerformanceId).toList();

    Map<Long, Performance> performanceById =
        performanceRepository.findAllById(performanceIds).stream()
            .collect(Collectors.toMap(Performance::getId, Function.identity()));

    return banners.stream()
        .filter(banner -> performanceById.containsKey(banner.getPerformanceId()))
        .map(
            banner -> {
              Performance performance = performanceById.get(banner.getPerformanceId());

              return bannerMapper.toResponse(
                  banner,
                  performance.getTitle(),
                  performance.getDescription(),
                  performance.getShowDate(),
                  performance.getImageMainUrl());
            })
        .toList();
  }
}
