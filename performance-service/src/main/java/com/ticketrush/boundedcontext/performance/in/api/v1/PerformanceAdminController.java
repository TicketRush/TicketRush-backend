package com.ticketrush.boundedcontext.performance.in.api.v1;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceChangeStatusRequest;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformancePatchRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceAdminSummaryResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceCreateResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceFileReplaceResponse;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Performance Admin", description = "공연 관리자 API")
@Validated
@RestController
@RequestMapping("/api/v1/performance/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PerformanceAdminController {

  private final PerformanceFacade performanceFacade;

  @Operation(
      summary = "관리자 공연 목록 조회",
      description =
          """
          전체 공연을 최신 등록순으로 페이징 조회합니다 (#563). 공개 목록과 달리 판매 좌석 수·점유율·매출 같은 관리 집계를 함께 내립니다.

          **모수는 삭제된 공연만 제외한 전체입니다** — 판매 전(UPCOMING)·취소(CANCELED) 공연도 포함합니다.

          **판매 좌석 수와 전체 좌석 수는 좌석 서비스의 실제 좌석 상태입니다.** 공연 등록 시 입력한 총 좌석 수와는 무관합니다.

          집계 필드는 원본 서비스 조회 실패 시 **그 필드만 null**로 내려가고 목록 자체는 성공합니다 —
          매출은 예매 서비스, 판매 좌석·점유율·매진은 좌석 서비스가 원본입니다.
          좌석이 아직 생성되지 않은 공연(등록 직후)도 좌석 관련 필드가 null입니다.
          `revenue`가 0과 null로 갈리는 것이 이 구분입니다: 0은 확정된 예매가 없다는 뜻이고 null은 값을 읽지 못했다는 뜻입니다.

          정렬 파라미터는 받지 않습니다.
          """)
  @GetMapping
  public ResponseEntity<ApiResponse<List<PerformanceAdminSummaryResponse>>> getAdminPerformances(
      @ParameterObject @ModelAttribute OffsetPageRequest pageRequest) {
    Page<PerformanceAdminSummaryResponse> response =
        performanceFacade.getAdminPerformances(pageRequest);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "공연 등록",
      description =
          """
          새로운 공연 정보를 등록합니다.

          **요청 형식:** `multipart/form-data`
          - `request` 파트: 공연 정보 JSON (Content-Type: application/json)
          - `mainImage` 파트: 메인 이미지 파일 — `jpg`, `jpeg`, `png` / 최대 5MB
          - `model3d` 파트: 3D 모델 파일 — `glb`, `obj` / 최대 10MB
          - `gallery` 파트: 갤러리 이미지 파일 (선택, 최대 3개) — `jpg`, `jpeg`, `png` / 각 최대 5MB

          업로드한 파일은 S3에 저장되며, 응답의 `imageMainUrl`·`image3dUrl`·`imageGalleryUrls`는
          인증 없이 바로 GET 할 수 있는 공개 URL입니다.

          **형식 판정은 파일명 확장자로만 합니다.** 브라우저가 보내는 `Content-Type`은 검증하지 않습니다
          (클라이언트가 임의로 지정할 수 있어 신뢰할 수 없습니다). 확장자가 파트와 맞지 않으면 400,
          크기 상한을 넘으면 413으로 거절합니다.

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
  @PatchMapping("/{id}/status")
  public ResponseEntity<ApiResponse<Void>> changePerformanceStatus(
      @PathVariable Long id,
      @org.springframework.web.bind.annotation.RequestBody @Valid
          PerformanceChangeStatusRequest request) {

    performanceFacade.changePerformanceStatus(id, request);

    return ApiResponse.onSuccess(SuccessStatus.OK);
  }

  @Operation(summary = "공연 정보 수정", description = "공연 정보를 부분 수정합니다. null 필드는 수정하지 않습니다.")
  @PatchMapping("/{id}")
  public ResponseEntity<ApiResponse<Void>> patchPerformance(
      @Parameter(description = "공연 ID") @Positive @PathVariable Long id,
      @org.springframework.web.bind.annotation.RequestBody @Valid PerformancePatchRequest request) {

    performanceFacade.patchPerformance(id, request);

    return ApiResponse.onSuccess(SuccessStatus.OK);
  }

  @Operation(
      summary = "공연 파일 교체",
      description =
          """
          등록된 공연의 파일을 교체합니다. **보낸 파트만 교체되고 보내지 않은 파트는 기존 파일이 그대로 유지됩니다.**

          **요청 형식:** `multipart/form-data` — 세 파트 모두 선택이지만 **실제 파일이 하나 이상 있어야 합니다.**
          - `mainImage` 파트: 새 메인 이미지 파일 — `jpg`, `jpeg`, `png` / 최대 5MB
          - `model3d` 파트: 새 3D 모델 파일 — `glb`, `obj` / 최대 10MB
          - `gallery` 파트: 새 갤러리 이미지 파일 (최대 3개) — `jpg`, `jpeg`, `png` / 각 최대 5MB

          **비어 있는 파트는 보내지 않은 것으로 처리합니다.** 파일을 고르지 않은 `<input type="file">`도
          0바이트 파트로 전송되기 때문에, 이를 거절하면 "입력 여러 개 중 하나만 골랐다"는 흔한 폼이 전부 실패합니다.
          그래서 0바이트 파트는 조용히 무시되며, 그 파트의 기존 파일은 유지됩니다.
          내용이 빈 파일을 실수로 올리면 오류 없이 200이 오고 해당 URL이 그대로이므로,
          **응답의 URL로 실제 교체 여부를 확인하세요.** 세 파트가 모두 비어 있으면 400으로 거절합니다.

          **갤러리는 전체 치환입니다.** `gallery`를 보내면 기존 갤러리 전체가 보낸 목록으로 대체됩니다.
          일부만 바꾸거나 개별 삭제하는 방법은 없으며, 갤러리를 비우는 것도 지원하지 않습니다.

          응답은 교체 후의 현재 URL 세 종입니다. 저장 키에 UUID를 쓰므로 교체하면 URL이 반드시 바뀌며,
          이 응답의 URL을 그대로 쓰면 상세를 다시 조회할 필요가 없습니다.

          교체 전 파일은 스토리지에 그대로 남습니다 — 이전 URL을 캐싱하고 있던 클라이언트가 즉시 깨지지 않도록 한 선택입니다.

          **형식 판정은 파일명 확장자로만 합니다.** 확장자가 파트와 맞지 않으면 400, 크기 상한을 넘으면 413으로 거절하며,
          이 경우 어떤 파일도 교체되지 않고 기존 URL이 그대로 남습니다.
          """)
  @RequestBody(
      content =
          @Content(
              mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
              schema = @Schema(implementation = PerformanceFileReplaceSwaggerBody.class)))
  @PatchMapping(value = "/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<PerformanceFileReplaceResponse>> replacePerformanceFiles(
      @Parameter(description = "공연 ID") @Positive @PathVariable Long id,
      @RequestPart(value = "mainImage", required = false) MultipartFile mainImage,
      @RequestPart(value = "model3d", required = false) MultipartFile model3d,
      @RequestPart(value = "gallery", required = false) List<MultipartFile> gallery) {

    PerformanceFileReplaceResponse response =
        performanceFacade.replacePerformanceFiles(id, mainImage, model3d, gallery);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "예매 오픈 시각 해제",
      description =
          "공연의 예매 오픈 시각을 해제해 스케줄러 자동 전환 대상에서 제외합니다. "
              + "이미 해제된 공연에 요청해도 성공합니다. "
              + "공연 상태는 변경되지 않으므로, 이미 판매 중인 공연을 중단하려면 상태 변경 API를 사용해야 합니다.")
  @DeleteMapping("/{id}/booking-open-at")
  public ResponseEntity<ApiResponse<Void>> clearBookingOpenAt(
      @Parameter(description = "공연 ID") @Positive @PathVariable Long id) {

    performanceFacade.clearBookingOpenAt(id);

    return ApiResponse.onSuccess(SuccessStatus.OK);
  }

  @Operation(summary = "공연 삭제", description = "공연을 논리 삭제합니다. 삭제된 공연은 모든 조회에서 제외됩니다.")
  @DeleteMapping("/{id}")
  public ResponseEntity<ApiResponse<Void>> deletePerformance(
      @Parameter(description = "공연 ID") @Positive @PathVariable Long id) {

    performanceFacade.deletePerformance(id);

    return ApiResponse.onSuccess(SuccessStatus.OK);
  }
}
