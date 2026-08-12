package com.ticketrush.boundedcontext.banner.app.usecase;

import com.ticketrush.boundedcontext.banner.app.dto.response.BannerResponse;
import com.ticketrush.boundedcontext.banner.app.mapper.BannerMapper;
import com.ticketrush.boundedcontext.banner.out.repository.BannerRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BannerGetListUseCase {

  private final BannerRepository bannerRepository;
  private final BannerMapper bannerMapper;

  /**
   * 노출 중인 배너를 순서대로 반환한다. 배너가 없으면 빈 목록이며, 프론트는 그 경우 배너 영역 자체를 렌더하지 않는다.
   *
   * <p>페이징하지 않는다 — 배너는 캐러셀에 전부 실리는 3~5건이고, 슬라이드 개수만큼 인디케이터를 그리는 화면이라 부분 응답이 의미가 없다.
   */
  @Transactional(readOnly = true)
  public List<BannerResponse> execute() {
    return bannerRepository.findByDeactivatedAtIsNullOrderByDisplayOrderAscIdAsc().stream()
        .map(bannerMapper::toResponse)
        .toList();
  }
}
