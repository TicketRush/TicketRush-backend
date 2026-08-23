package com.ticketrush.boundedcontext.performance.app.mapper;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceCreateResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceDetailResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PerformanceMapper {

  @Mapping(target = "imageMainUrl", ignore = true)
  @Mapping(target = "image3dUrl", ignore = true)
  @Mapping(target = "imageGalleryUrls", ignore = true)
  Performance toEntity(PerformanceCreateRequest request);

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
  PerformanceDetailResponse toDetailResponse(Performance performance);
}
