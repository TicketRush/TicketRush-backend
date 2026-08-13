package com.ticketrush.boundedcontext.booking.in.api.v1;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingInternalResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingInternalStatsResponse;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetInternalStatsUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetInternalUseCase;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden // 내부 전용 API — 공개 OpenAPI 문서(/v3/api-docs)에 노출하지 않는다.
@Tag(name = "BookingInternal", description = "예매 내부 통신 API")
// @Validated는 파라미터 제약 위반을 ConstraintViolationException으로 던지게 해 GlobalExceptionHandler의
// 400 매핑에 태운다. 없으면 HandlerMethodValidationException이 되는데 그건 핸들러 목록에 없어 catch-all로
// 떨어져 500이 나간다 — 호출자가 fail-open이라 그 500은 "예매 서비스 장애"로 조용히 흡수된다.
@Validated
@RestController
@RequestMapping("/api/v1/internal/booking")
@RequiredArgsConstructor
public class BookingInternalController {

  private final BookingGetInternalUseCase bookingGetInternalUseCase;
  private final BookingGetInternalStatsUseCase bookingGetInternalStatsUseCase;

  /**
   * 이 매핑은 {@code /{bookingId}}보다 먼저 평가된다 — Spring의 {@code PathPattern}이 리터럴 세그먼트를 변수 세그먼트보다 구체적으로
   * 보기 때문이다. 순서에 기대는 코드라 경로를 바꿀 때 함께 확인해야 한다.
   */
  @Operation(
      summary = "예매 집계 내부 조회",
      description =
          """
          관리자 화면(#563 대시보드, #590 공연 목록)이 쓰는 예매 집계입니다.

          **요청한 축만 계산합니다.** 계산하지 않은 축은 응답에서 키째 빠집니다(전역 NON_NULL).

          | performance_ids | from·to | 응답 | 호출자 |
          |---|---|---|---|
          | 없음 | 있음 | `summary` + `by_performance` + `by_date` | 관리자 대시보드 |
          | 없음 | 없음 | `summary` + `by_performance` | - |
          | 있음 | 무관 | `by_performance`만 | 관리자 공연 목록 |

          **`performance_ids`를 주면 요약·일별 쿼리를 아예 돌리지 않습니다 (#590).** 관리자 공연 목록은
          페이지마다 이 API를 부르는데, 안 쓸 값을 위해 예매 테이블을 두 번 더 전건 스캔하지 않기 위해서입니다.

          **요약은 관리자 예매 통계 API(`/api/v1/booking/admin/bookings/stats`)와 같은 값입니다** —
          같은 유스케이스를 재사용하므로 두 화면의 매출이 갈리지 않습니다. 같은 이유로 요약은
          `performance_ids`로 좁히지 않습니다.

          **기간은 일별 매출에만 적용됩니다.** 요약과 공연별 집계는 전체 기간입니다.
          기간의 기본값·상한은 이 API가 아니라 호출자(관리자 대시보드)가 정합니다.
          """)
  @GetMapping("/stats")
  public ResponseEntity<ApiResponse<BookingInternalStatsResponse>> getStats(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      // 상한은 호출 측 페이지 상한(공유 상수 MAX_PAGE_SIZE, 현재 50)보다 넉넉하게 잡는다. 같은 값으로 묶으면
      // 누가 공유 상수를 올리는 순간 이 경로만 400이 되고, 그 400은 호출 측 fail-open이 흡수해 매출 컬럼이
      // 전부 비는 형태로만 드러난다 — 원인 추적이 로그 한 줄에 달린다. user-service도 같은 이유로 여유를 뒀다.
      @RequestParam(required = false) @Size(min = 1, max = 120) List<Long> performanceIds) {
    return ApiResponse.onSuccess(
        SuccessStatus.OK, bookingGetInternalStatsUseCase.execute(from, to, performanceIds));
  }

  @Operation(summary = "예매 소유자/상태 내부 조회", description = "예매의 소유자와 상태를 반환합니다.")
  @GetMapping("/{bookingId}")
  public ResponseEntity<ApiResponse<BookingInternalResponse>> getBookingInternal(
      @PathVariable Long bookingId) {
    BookingInternalResponse response = bookingGetInternalUseCase.execute(bookingId);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }
}
