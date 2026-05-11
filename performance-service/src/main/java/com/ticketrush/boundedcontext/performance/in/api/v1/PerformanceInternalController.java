package com.ticketrush.boundedcontext.performance.in.api.v1;

import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.boundedcontext.performance.in.api.v1.swagger.PerformanceValidateApiResponses;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Performance", description = "공연 API")
@RestController
@RequestMapping("/api/v1/performance")
@RequiredArgsConstructor
public class PerformanceInternalController {

  private final PerformanceFacade performanceFacade;

  @Operation(summary = "공연 유효성 검증", description = "공연이 존재하고 예매 가능(ON_SALE) 상태인지 검증합니다.")
  @PerformanceValidateApiResponses
  @GetMapping("/{id}/validate")
  public ResponseEntity<ApiResponse<Void>> validatePerformance(@PathVariable Long id) {
    performanceFacade.validatePerformance(id);
    return ApiResponse.onSuccess(SuccessStatus.OK);
  }
}
