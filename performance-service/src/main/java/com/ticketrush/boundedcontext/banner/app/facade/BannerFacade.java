package com.ticketrush.boundedcontext.banner.app.facade;

import com.ticketrush.boundedcontext.banner.app.dto.response.BannerResponse;
import com.ticketrush.boundedcontext.banner.app.usecase.BannerGetListUseCase;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BannerFacade {

  private final BannerGetListUseCase bannerGetListUseCase;

  public List<BannerResponse> getBanners() {
    return bannerGetListUseCase.execute();
  }
}
