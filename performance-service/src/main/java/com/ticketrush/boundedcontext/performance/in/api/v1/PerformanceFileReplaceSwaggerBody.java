package com.ticketrush.boundedcontext.performance.in.api.v1;

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

  @Schema(
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
   */
  @ArraySchema(
      schema = @Schema(type = "string", format = "binary"),
      maxItems = FileKind.GALLERY_MAX_COUNT,
      arraySchema =
          @Schema(description = "새 갤러리 이미지 파일 (선택) — 보내면 기존 갤러리 전체를 이 목록으로 대체합니다", nullable = true))
  public List<MultipartFile> gallery;
}
