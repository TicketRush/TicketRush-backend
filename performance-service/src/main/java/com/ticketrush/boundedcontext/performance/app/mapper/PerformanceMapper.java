package com.ticketrush.boundedcontext.performance.app.mapper;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceCreateResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceDetailResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.policy.PerformanceShowTimePolicy;
import java.time.LocalDateTime;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Mapper(componentModel = "spring")
public interface PerformanceMapper {

  /**
   * 캐릭터 구성 JSON의 파싱 전용 매퍼(#650). 전역 {@code JacksonConfig}(SNAKE_CASE·NON_NULL)를 일부러 타지 않는다. 저장된
   * 문자열을 트리로 읽기만 하므로 키 변환이 개입할 자리가 없고, 그래야 "저장한 값이 그대로 응답에 실린다"가 성립한다.
   */
  JsonMapper CHARACTER_CONFIG_MAPPER = JsonMapper.builder().build();

  /**
   * 캐릭터 구성은 {@link #toJsonString}으로, 한마디는 {@link #blankToNull}로 정규화해 넣는다(#650). 등록에서 빈 한마디는 "없음"이다.
   * PATCH의 빈 문자열=삭제 규칙과 같은 결과가 되도록 맞춘다.
   */
  @Mapping(target = "imageMainUrl", ignore = true)
  @Mapping(target = "image3dUrl", ignore = true)
  @Mapping(target = "imageGalleryUrls", ignore = true)
  @Mapping(target = "characterConfig", source = "characterConfig", qualifiedByName = "toJsonString")
  @Mapping(
      target = "characterMessage",
      source = "characterMessage",
      qualifiedByName = "blankToNull")
  Performance toEntity(PerformanceCreateRequest request);

  /**
   * 요청의 JSON 트리를 저장용 compact 문자열로 바꾼다. JSON {@code null}은 {@code NullNode}로 바인딩되므로 Java null과 같이 "값
   * 없음"으로 본다.
   */
  @Named("toJsonString")
  default String toJsonString(JsonNode node) {
    return (node == null || node.isNull()) ? null : node.toString();
  }

  /** 저장된 JSON 문자열을 응답용 트리로 되돌린다. 저장 시 객체임을 검증한 값이라 파싱 실패는 정상 경로에 없다. */
  @Named("toJsonNode")
  default JsonNode toJsonNode(String json) {
    return json == null ? null : CHARACTER_CONFIG_MAPPER.readTree(json);
  }

  /** 쪼개 저장된 공연 시작 일시를 응답용 {@code showAt}으로 합친다(#671). */
  @Named("toShowAt")
  default LocalDateTime toShowAt(Performance performance) {
    return PerformanceShowTimePolicy.showAt(performance.getShowDate(), performance.getShowTime());
  }

  @Named("blankToNull")
  default String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }

  @Mapping(source = "id", target = "performanceId")
  PerformanceCreateResponse toCreateResponse(Performance performance);

  /** 좌석 필드 2개를 반드시 무시한다(#176). 좌석 값은 좌석 서비스 조회 결과로 {@code PerformanceGetListUseCase}에서 따로 적용한다. */
  @Mapping(source = "id", target = "performanceId")
  @Mapping(target = "totalSeats", ignore = true)
  @Mapping(target = "remainingSeats", ignore = true)
  @Mapping(target = "showAt", source = ".", qualifiedByName = "toShowAt")
  PerformanceListResponse toListResponse(Performance performance);

  /** 배너 정보는 Performance 엔티티의 필드가 아니므로 자동 매핑하지 않는다. 상세 조회 UseCase에서 Banner를 조회한 뒤 별도로 적용한다. */
  @Mapping(source = "id", target = "performanceId")
  @Mapping(target = "characterConfig", source = "characterConfig", qualifiedByName = "toJsonNode")
  @Mapping(target = "showAt", source = ".", qualifiedByName = "toShowAt")
  @Mapping(target = "displayOnBanner", ignore = true)
  @Mapping(target = "bannerSubtitle", ignore = true)
  PerformanceDetailResponse toDetailResponse(Performance performance);
}
