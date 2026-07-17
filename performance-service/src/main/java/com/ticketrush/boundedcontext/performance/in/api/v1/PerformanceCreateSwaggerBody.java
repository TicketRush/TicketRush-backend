package com.ticketrush.boundedcontext.performance.in.api.v1;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

@Schema(description = "공연 등록 multipart/form-data 요청")
public class PerformanceCreateSwaggerBody {

  @Schema(description = "공연 정보 JSON")
  public PerformanceCreateRequest request;

  @Schema(type = "string", format = "binary", description = "메인 이미지 파일")
  public MultipartFile mainImage;

  @Schema(type = "string", format = "binary", description = "3D 모델 파일")
  public MultipartFile model3d;

  @Schema(
      type = "string",
      format = "binary",
      description = "갤러리 이미지 파일 (선택, 최대 3개)",
      nullable = true)
  public List<MultipartFile> gallery;
}
