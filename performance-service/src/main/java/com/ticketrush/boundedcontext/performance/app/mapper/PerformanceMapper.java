package com.ticketrush.boundedcontext.performance.app.mapper;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceCreateResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceDetailResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Mapper(componentModel = "spring")
public interface PerformanceMapper {

  /**
   * 캐릭터 구성 JSON의 파싱 전용 매퍼(#650). 전역 {@code JacksonConfig}(SNAKE_CASE·NON_NULL)를 일부러 타지 않는다 — 저장된
   * 문자열을 트리로 읽기만 하므로 키 변환이 개입할 자리가 없고, 그래야 "저장한 값이 그대로 응답에 실린다"가 성립한다.
   */
  JsonMapper CHARACTER_CONFIG_MAPPER = JsonMapper.builder().build();

  /**
   * 캐릭터 구성은 {@link #toJsonString}으로, 한마디는 {@link #blankToNull}로 정규화해 넣는다(#650). 등록에서 빈 한마디는 "없음"이다
   * — PATCH의 빈 문자열=삭제 규칙({@code Performance.updateCharacter})과 같은 결과가 되도록 맞춘다.
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
   * 없음"으로 본다. MapStruct가 {@code JsonNode → String} 변환이 필요한 자리(등록 매핑)에서 타입만 맞으면 자동으로 고르지만, 같은 타입 매핑이
   * 나중에 생겨도 조용히 재사용되지 않도록 {@code @Named}로 자리를 명시한다.
   */
  @Named("toJsonString")
  default String toJsonString(JsonNode node) {
    return (node == null || node.isNull()) ? null : node.toString();
  }

  /**
   * 저장된 JSON 문자열을 응답용 트리로 되돌린다. 저장 시 객체임을 검증한 값이라 파싱 실패는 정상 경로에 없다. MapStruct가 {@code String →
   * JsonNode} 변환이 필요한 자리(상세 응답)에서 고른다 — {@link #toJsonString}과 같은 이유로 {@code @Named}로 명시한다.
   */
  @Named("toJsonNode")
  default JsonNode toJsonNode(String json) {
    return json == null ? null : CHARACTER_CONFIG_MAPPER.readTree(json);
  }

  @Named("blankToNull")
  default String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }

  @Mapping(source = "id", target = "performanceId")
  PerformanceCreateResponse toCreateResponse(Performance performance);

  /**
   * 좌석 필드 2개를 <b>반드시</b> 무시한다 (#176).
   *
   * <p>{@code totalSeats}는 엔티티에도 같은 이름으로 있어, 막지 않으면 MapStruct가 {@code Integer → Long} 변환까지 해가며
   * {@code performance.getTotalSeats()}를 자동으로 채운다(생성된 구현체에서 확인). 그 값은 공연 등록 시 입력값이라 좌석 서비스의 실제 좌석
   * 수와 무관하다.
   *
   * <p><b>이 방어가 풀려도 좌석 서비스가 정상인 동안에는 드러나지 않는다.</b> 좌석 수를 받아 오면 유스케이스가 덮어쓰기 때문이다. 대신 조회에 실패했거나 좌석이
   * 아직 생성되지 않은 공연에서는 등록 입력값이 그대로 남아, <b>좌석 서비스가 죽은 순간에만 엉뚱한 좌석 수가 응답에 실린다</b>. 비어 있어야 할 자리가 채워지는
   * 것이라 클라이언트는 그것이 실측값인지 구분할 수 없다. 컴파일 경고조차 나지 않으므로 이 {@code ignore}와 그것을 고정한 테스트가 유일한 방어다.
   *
   * <p>좌석 값은 좌석 서비스 조회 결과로 {@code PerformanceGetListUseCase}에서 따로 얹는다.
   */
  @Mapping(source = "id", target = "performanceId")
  @Mapping(target = "totalSeats", ignore = true)
  @Mapping(target = "remainingSeats", ignore = true)
  PerformanceListResponse toListResponse(Performance performance);

  @Mapping(source = "id", target = "performanceId")
  @Mapping(target = "characterConfig", source = "characterConfig", qualifiedByName = "toJsonNode")
  PerformanceDetailResponse toDetailResponse(Performance performance);
}
