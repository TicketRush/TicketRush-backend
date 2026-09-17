package com.ticketrush.boundedcontext.performance.in.api.v1;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

@Schema(description = "공연 등록 multipart/form-data 요청")
public class PerformanceCreateSwaggerBody {

  @Schema(description = "공연 정보 JSON")
  public PerformanceCreateRequest request;

  /*
   * name 을 직접 적는다. 이 클래스의 필드명은 JSON 프로퍼티가 아니라 @RequestPart 의 파트명이라 스키마
   * 네이밍 전략(#658)이 닿으면 안 된다. 닿으면 문서가 main_image 로 나가고, 그 문서를 믿은 클라이언트는
   * 메인 이미지만 조용히 교체에 실패한다(#637 에서 실제로 난 사고다).
   * model3d · gallery · request 는 소문자 한 덩어리라 변환 자체가 일어나지 않는다.
   */
  @Schema(name = "mainImage", type = "string", format = "binary", description = "메인 이미지 파일")
  public MultipartFile mainImage;

  @Schema(
      type = "string",
      format = "binary",
      description = "3D 모델 파일 (선택 — 캐릭터는 request 파트의 characterConfig로 등록)",
      nullable = true)
  public MultipartFile model3d;

  @Schema(
      type = "string",
      format = "binary",
      description = "갤러리 이미지 파일 (선택, 최대 3개)",
      nullable = true)
  public List<MultipartFile> gallery;
}
