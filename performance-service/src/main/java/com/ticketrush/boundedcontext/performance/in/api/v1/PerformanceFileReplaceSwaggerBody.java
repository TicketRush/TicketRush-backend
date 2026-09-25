package com.ticketrush.boundedcontext.performance.in.api.v1;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceFileReplaceRequest;
import com.ticketrush.global.util.FileKind;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 파일 교체 요청의 multipart 파트 구조를 springdoc에 알리는 껍데기다.
 *
 * <p>springdoc이 {@code @RequestPart} 여러 개를 하나의 multipart 스키마로 조립하지 못해, 등록 API의 {@code
 * PerformanceCreateSwaggerBody}와 같은 방식으로 파트 구조만 나타낸다. 실제 바인딩에는 쓰이지 않는다.
 */
@Schema(description = "공연 파일 교체 multipart/form-data 요청 — 보낸 파트만 교체됩니다")
public class PerformanceFileReplaceSwaggerBody {

  /*
   * 파트명 request 는 소문자 한 덩어리라 스키마 네이밍 전략(#658)이 닿아도 바뀌지 않는다. 안쪽 JSON 키
   * (keep_gallery_urls·clear_model3d)는 참조 스키마의 프로퍼티라 전략이 적용되어 snake 로 나가야 맞다.
   */
  @Schema(
      description =
          "변경 지시 JSON (선택) — keep_gallery_urls: 유지할 기존 갤러리 URL 목록, clear_model3d: 3D 모델 비우기. "
              + "보내지 않으면 파일 파트만으로 동작하는 기존 계약과 같다",
      nullable = true)
  public PerformanceFileReplaceRequest request;

  /*
   * name 을 직접 적는 이유는 등록 쪽 PerformanceCreateSwaggerBody 와 같다 — 파트명은 스키마 네이밍
   * 전략(#658)의 대상이 아니다.
   */
  @Schema(
      name = "mainImage",
      type = "string",
      format = "binary",
      description = "새 메인 이미지 파일 (선택) — jpg, jpeg, png / 최대 5MB",
      nullable = true)
  public MultipartFile mainImage;

  @Schema(
      type = "string",
      format = "binary",
      description = "새 3D 모델 파일 (선택) — glb, obj / 최대 10MB",
      nullable = true)
  public MultipartFile model3d;

  /*
   * 단일 binary가 아니라 배열로 싣는다. 이 파트는 "보내면 전체 치환"이라 배열 여부가 데이터 파괴로 직결되는데,
   * 개수를 산문으로만 적어 두면 스펙으로 클라이언트를 생성하는 쪽이 파일 하나짜리 필드를 만든다.
   * maxItems 는 이 파트 단독 상한이다 — request 의 유지 목록과 합친 상한(같은 값)은 유스케이스가 검사한다.
   */
  @ArraySchema(
      schema = @Schema(type = "string", format = "binary"),
      maxItems = FileKind.GALLERY_MAX_COUNT,
      arraySchema =
          @Schema(
              description =
                  "새 갤러리 이미지 파일 (선택). request 의 keep_gallery_urls 를 보내지 않으면 기존 갤러리 전체를 이 목록으로 "
                      + "대체하고, 보내면 유지 목록 뒤에 이 순서대로 덧붙입니다",
              nullable = true))
  public List<MultipartFile> gallery;
}
