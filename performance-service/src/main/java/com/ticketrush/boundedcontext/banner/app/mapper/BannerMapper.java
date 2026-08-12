package com.ticketrush.boundedcontext.banner.app.mapper;

import com.ticketrush.boundedcontext.banner.app.dto.response.BannerResponse;
import com.ticketrush.boundedcontext.banner.domain.entity.Banner;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BannerMapper {

  /** 이름이 갈리는 세 필드의 근거는 {@link BannerResponse} javadoc에 있다. */
  @Mapping(source = "displayDate", target = "date")
  @Mapping(source = "linkPerformanceId", target = "linkConcertId")
  @Mapping(source = "displayOrder", target = "order")
  BannerResponse toResponse(Banner banner);
}
