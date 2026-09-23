package com.ticketrush.boundedcontext.banner.app.mapper;

import com.ticketrush.boundedcontext.banner.app.dto.response.BannerResponse;
import com.ticketrush.boundedcontext.banner.domain.entity.Banner;
import java.time.LocalDate;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BannerMapper {

  @Mapping(source = "banner.id", target = "id")
  @Mapping(source = "banner.performanceId", target = "performanceId")
  @Mapping(source = "title", target = "title")
  @Mapping(source = "banner.subtitle", target = "subtitle")
  @Mapping(source = "description", target = "description")
  @Mapping(source = "date", target = "date")
  @Mapping(source = "imageUrl", target = "imageUrl")
  @Mapping(source = "banner.displayOrder", target = "order")
  BannerResponse toResponse(
      Banner banner, String title, String description, LocalDate date, String imageUrl);
}
