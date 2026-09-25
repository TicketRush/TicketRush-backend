package com.ticketrush.boundedcontext.performance.app.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 파일 교체 요청의 변경 지시(#688). 파일 파트만으로는 표현할 수 없는 "기존 것을 남긴다·비운다"를 싣는다.
 *
 * <p><b>삭제 목록이 아니라 유지 목록이다(ADR 0023).</b> 삭제 목록을 두면 {@code gallery} 파트의 의미를 "전체 치환"에서 "추가"로 바꿔야
 * 일관되고 그러면 #637 계약이 깨진다. 유지 목록은 화면에 남아 있는 상태를 최종 목록으로 선언하는 방식이라 여전히 전체 치환의 성격이고, 같은 요청을 다시 보내도 결과가
 * 같다.
 *
 * <p><b>{@code null}과 빈 목록은 다르다.</b> {@code keepGalleryUrls}가 null(키 누락)이면 갤러리 규칙은 #637 그대로다(파일을
 * 보내면 전체 치환, 안 보내면 유지). 빈 목록이면 "기존은 하나도 남기지 않는다"이므로 신규 파일이 없으면 갤러리가 비워진다.
 *
 * <p>Bean Validation을 걸지 않는 이유는 규칙 전부가 현재 갤러리 상태(DB)를 봐야 판정되기 때문이다 — 유지 URL의 존재 여부는 물론이고 개수 상한도 유지
 * 목록 단독이 아니라 신규 파일과의 합으로 센다. 여기에 {@code @Size}를 걸면 같은 위반이 {@code VALID_400_001}과 {@code
 * PERFORMANCE_400_003} 두 코드로 갈려 프론트가 둘 다 처리해야 한다. 규칙은 {@code PerformanceReplaceFilesUseCase}가 한곳에서
 * 판정한다.
 */
@Schema(
    description =
        "공연 파일 교체의 변경 지시 (multipart `request` 파트, 선택). "
            + "보내지 않으면 파일 파트만으로 동작하는 기존 계약(gallery 를 보내면 전체 치환, 안 보내면 유지)과 같다")
public record PerformanceFileReplaceRequest(
    @Schema(
            description =
                "유지할 기존 갤러리 URL 목록. 최종 갤러리 = 이 목록(이 순서대로) + gallery 파트의 신규 파일. "
                    + "여기 없는 기존 URL은 빠진다. 빈 배열 + 신규 없음 = 갤러리 비우기. "
                    + "키를 생략하면 기존 규칙(gallery 를 보내면 전체 치환, 안 보내면 유지). "
                    + "현재 갤러리에 없는 URL·중복 URL은 400",
            nullable = true)
        List<String> keepGalleryUrls,
    @Schema(
            description =
                "true 면 3D 모델 URL을 비운다. 새 model3d 파일과 함께 보내면 모순이라 400. null/false 는 아무 지시도 아니다",
            nullable = true)
        Boolean clearModel3d) {

  /** {@code clear_model3d: true}를 명시했을 때만 참이다. null·false는 "지시 없음"으로 같다. */
  public boolean clearsModel3d() {
    return Boolean.TRUE.equals(clearModel3d);
  }

  /** 유지 목록을 보냈는가(빈 배열 포함). 키를 생략한 null 만 거짓이다. */
  public boolean hasKeepGalleryUrls() {
    return keepGalleryUrls != null;
  }
}
