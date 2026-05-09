package com.ticketrush.boundedcontext.performance.in.api.v1;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.in.api.v1.swagger.PerformanceListApiResponses;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Performance", description = "공연 API")
@RestController
@RequestMapping("/api/v1/performance")
@RequiredArgsConstructor
public class PerformanceController {

  private final PerformanceFacade performanceFacade;

  @Operation(
      summary = "공연 목록 조회",
      description =
          """
          메인 화면에 노출될 공연 목록을 조회합니다.

          슬라이드 영역과 그리드 목록 모두 이 API를 사용합니다.

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
  @PerformanceListApiResponses
  @GetMapping
  public ResponseEntity<ApiResponse<List<PerformanceListResponse>>> getPerformances(
      @Parameter(description = "장르 필터 (미입력 시 전체 조회)") @RequestParam(required = false) Genre genre,
      @ParameterObject
          @PageableDefault(size = 8, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {

    Page<PerformanceListResponse> performances = performanceFacade.getPerformances(genre, pageable);

    return ApiResponse.onSuccess(SuccessStatus.OK, performances);
  }
}
