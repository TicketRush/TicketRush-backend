package com.ticketrush.boundedcontext.performance.in.api.v1;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceChangeStatusRequest;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceCreateResponse;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Performance Admin", description = "공연 관리자 API")
@RestController
@RequestMapping("/api/v1/performance")
@RequiredArgsConstructor
public class PerformanceAdminController {

  private final PerformanceFacade performanceFacade;

  @Operation(
      summary = "공연 등록",
      description =
          """
          새로운 공연 정보를 등록합니다.

          **요청 형식:** `multipart/form-data`
          - `request` 파트: 공연 정보 JSON (Content-Type: application/json)
          - `mainImage` 파트: 메인 이미지 파일
          - `model3d` 파트: 3D 모델 파일
          - `gallery` 파트: 갤러리 이미지 파일 (선택, 최대 3개)

          **장르 코드:**
          | 코드 | 설명 |
          |------|------|
          | MUSICAL | 뮤지컬 |
          | CONCERT | 콘서트 |
          | CLASSIC | 클래식 |
          | JAZZ | 재즈 |
          | FESTIVAL | 페스티벌 |
          | BALLET | 발레/무용 |
          | FANMEETING | 팬미팅 |
          """)
  @PerformanceCreateApiResponses
  @RequestBody(
      content =
          @Content(
              mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
              schema = @Schema(implementation = PerformanceCreateSwaggerBody.class),
              encoding =
                  @Encoding(name = "request", contentType = MediaType.APPLICATION_JSON_VALUE)))
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<PerformanceCreateResponse>> createPerformance(
      @RequestPart("request") @Valid PerformanceCreateRequest request,
      @RequestPart("mainImage") MultipartFile mainImage,
      @RequestPart("model3d") MultipartFile model3d,
      @RequestPart(value = "gallery", required = false) List<MultipartFile> gallery) {

    PerformanceCreateResponse response =
        performanceFacade.createPerformance(request, mainImage, model3d, gallery);

    return ApiResponse.onSuccess(SuccessStatus.CREATED, response);
  }

  @Operation(summary = "공연 상태 변경", description = "공연의 상태를 변경합니다.")
  @PerformanceChangeStatusApiResponses
  @PatchMapping("/{id}/status")
  public ResponseEntity<ApiResponse<Void>> changePerformanceStatus(
      @PathVariable Long id,
      @org.springframework.web.bind.annotation.RequestBody @Valid
          PerformanceChangeStatusRequest request) {

    performanceFacade.changePerformanceStatus(id, request);

    return ApiResponse.onSuccess(SuccessStatus.OK);
  }
}
