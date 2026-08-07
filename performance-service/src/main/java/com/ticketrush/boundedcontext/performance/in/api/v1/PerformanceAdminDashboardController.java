package com.ticketrush.boundedcontext.performance.in.api.v1;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceAdminDashboardResponse;
import com.ticketrush.boundedcontext.performance.app.facade.PerformanceFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 대시보드 집계 API (#563).
 *
 * <p><b>경로가 반드시 {@code /api/v1/performance/admin} 아래여야 한다.</b> SecurityConfig는 그 prefix만 {@code
 * hasRole("ADMIN")}으로 걸고 나머지는 {@code anyRequest().permitAll()}이라, prefix를 벗어나면 경로 매처가 걸리지 않고 클래스
 * 어노테이션 하나만 남는다.
 */
@Tag(name = "Performance Admin Dashboard", description = "공연 관리자 대시보드 API")
@RestController
@RequestMapping("/api/v1/performance/admin/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PerformanceAdminDashboardController {

  private final PerformanceFacade performanceFacade;

  @Operation(
      summary = "관리자 대시보드 집계 조회",
      description =
          """
          대시보드 요약 카드와 매출 분석 데이터를 한 번에 조회합니다 (#563).

          **지표마다 모수가 다릅니다.**
          - 등록 공연 수 — 삭제된 공연만 제외한 전체(판매 전·취소 포함)
          - 평균 점유율 — 판매가 실제로 일어나는 공연(ON_SALE·CLOSED)만
          - 총 매출·판매 티켓 수 — 상태 무관 전체이며 관리자 예매 통계 API와 같은 값

          **기간(`from`/`to`)은 일별 매출 추이에만 적용됩니다.** 요약 카드와 장르별 분포는 전체 기간입니다.
          미지정 시 오늘을 포함한 최근 30일이며, 최대 92일까지 조회할 수 있습니다.
          기간이 뒤집혔거나 상한을 넘으면 `COMMON_400`으로 거부합니다 — 조용히 잘라내면 화면에 표시된 기간과
          실제 집계 기간이 어긋나기 때문입니다.

          **평균 점유율은 가중 평균입니다** — 공연별 점유율의 산술평균이 아니라 전체 판매 좌석 ÷ 전체 좌석이라
          매출·티켓 수와 같은 축입니다. 검증할 수 있도록 분자·분모를 함께 내립니다.

          예매·좌석 서비스 조회가 실패하면 **그 축의 필드만 null**로 내려가고 대시보드 자체는 성공합니다.
          null은 0이 아니라 "값을 읽지 못했다"는 뜻입니다.
          """)
  @GetMapping
  public ResponseEntity<ApiResponse<PerformanceAdminDashboardResponse>> getDashboard(
      @Parameter(description = "일별 매출 시작일(포함). 미지정 시 종료일 기준 최근 30일", example = "2026-07-09")
          @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate from,
      @Parameter(description = "일별 매출 종료일(포함). 미지정 시 오늘", example = "2026-08-07")
          @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate to) {
    return ApiResponse.onSuccess(SuccessStatus.OK, performanceFacade.getAdminDashboard(from, to));
  }
}
