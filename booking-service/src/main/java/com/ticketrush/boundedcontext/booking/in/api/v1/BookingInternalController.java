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
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden // 내부 전용 API — 공개 OpenAPI 문서(/v3/api-docs)에 노출하지 않는다.
@Tag(name = "BookingInternal", description = "예매 내부 통신 API")
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
          관리자 대시보드(#563)가 쓰는 예매 집계입니다. 요약·공연별·일별을 한 응답으로 내립니다.

          **요약은 관리자 예매 통계 API(`/api/v1/booking/admin/bookings/stats`)와 같은 값입니다** —
          같은 유스케이스를 재사용하므로 두 화면의 매출이 갈리지 않습니다.

          **기간은 일별 매출에만 적용됩니다.** 요약과 공연별 집계는 전체 기간입니다.
          기간의 기본값·상한은 이 API가 아니라 호출자(관리자 대시보드)가 정합니다.
          """)
  @GetMapping("/stats")
  public ResponseEntity<ApiResponse<BookingInternalStatsResponse>> getStats(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ApiResponse.onSuccess(
        SuccessStatus.OK, bookingGetInternalStatsUseCase.execute(from, to));
  }

  @Operation(summary = "예매 소유자/상태 내부 조회", description = "예매의 소유자와 상태를 반환합니다.")
  @GetMapping("/{bookingId}")
  public ResponseEntity<ApiResponse<BookingInternalResponse>> getBookingInternal(
      @PathVariable Long bookingId) {
    BookingInternalResponse response = bookingGetInternalUseCase.execute(bookingId);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }
}
