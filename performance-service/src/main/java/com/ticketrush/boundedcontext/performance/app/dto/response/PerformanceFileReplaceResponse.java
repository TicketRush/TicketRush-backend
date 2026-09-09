package com.ticketrush.boundedcontext.performance.app.dto.response;

import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "공연 파일 교체 응답 — 교체 후의 현재 URL")
public record PerformanceFileReplaceResponse(
    @Schema(description = "메인 이미지 공개 URL") String imageMainUrl,
    @Schema(description = "3D 모델 공개 URL") String image3dUrl,
    @Schema(description = "갤러리 이미지 공개 URL 목록") List<String> imageGalleryUrls) {

  /**
   * 교체 후의 현재 URL 세 종을 담는다. 이번에 바꾸지 않은 파트는 기존 URL이 그대로 실린다.
   *
   * <p><b>MapStruct를 쓰지 않고 손으로 쓴다.</b> 이 record의 필드 셋은 전부 엔티티와 이름이 같아 매퍼로 만들면 MapStruct가 경고 없이 자동
   * 매핑한다. #176에서 그 자동 매핑이 실제 사고를 냈고({@code totalSeats}가 좌석 서비스 값이 아닌 엔티티 값으로 조용히 채워졌다), {@code
   * PerformanceMapper}의 방어 수단은 {@code ignore}와 그것을 고정한 테스트뿐이다. 여기서는 세 필드 모두 채워야 하는 값이라 {@code
   * ignore}로 막을 대상 자체가 없고, 나중에 필드가 추가되면 그때 자동 매핑이 조용히 붙는다.
   *
   * <p>갤러리를 {@link List#copyOf}로 복사하는 이유는 영속 컬렉션을 응답에 그대로 싣지 않기 위해서다. 트랜잭션이 끝난 뒤 직렬화되면 이미 닫힌 세션을
   * 건드리게 된다.
   */
  public static PerformanceFileReplaceResponse from(Performance performance) {
    return new PerformanceFileReplaceResponse(
        performance.getImageMainUrl(),
        performance.getImage3dUrl(),
        List.copyOf(performance.getImageGalleryUrls()));
  }
}
