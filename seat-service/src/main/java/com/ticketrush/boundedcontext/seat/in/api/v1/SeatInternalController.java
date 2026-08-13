package com.ticketrush.boundedcontext.seat.in.api.v1;

import com.ticketrush.boundedcontext.seat.app.dto.request.SeatHoldReleaseRequest;
import com.ticketrush.boundedcontext.seat.app.dto.request.SeatSoldConfirmRequest;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsByPerformanceResponse;
import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden // 내부 전용 API — 공개 OpenAPI 문서(/v3/api-docs)에 노출하지 않는다.
@Tag(name = "SeatInternal", description = "좌석 내부 통신 API")
// @Validated는 파라미터 제약 위반을 ConstraintViolationException으로 던지게 해 GlobalExceptionHandler의
// 400 매핑에 태운다. 없으면 HandlerMethodValidationException이 되는데 그건 핸들러 목록에 없어 catch-all로
// 떨어져 500이 나간다 — 호출자가 fail-open이라 그 500은 "좌석 서비스 장애"로 조용히 흡수된다.
@Validated
@RestController
@RequestMapping("/api/v1/internal/seat")
@RequiredArgsConstructor
public class SeatInternalController {

  private final SeatFacade seatFacade;

  @Operation(
      summary = "공연 좌석 수 일괄 조회",
      description =
          """
          공연의 좌석 수(전체·예매가능·판매완료·임시선점)를 한 번에 반환합니다 (#563 관리자 대시보드).

          `performance_ids`를 주면 그 공연들만, 주지 않으면 전 공연을 집계합니다 (#590).
          관리자 대시보드는 전 공연이 모수라 생략하고, 관리자 공연 목록은 현재 페이지의 공연 ID만 실어 보냅니다.

          공연별 단건 조회(`GET /api/v1/seat/{performanceId}/seat-counts`)와 세는 규칙이 같습니다 —
          만료된 임시선점은 예매가능에 포함되므로 `total_count = available_count + sold_count + hold_count`입니다.

          **좌석이 아직 생성되지 않은 공연은 응답에 포함되지 않습니다.** 좌석 생성은 공연 등록 이벤트를 받아
          비동기로 일어나므로, 방금 등록된 공연은 이 목록에 없을 수 있습니다. 호출자는 그 공연을 0석으로 채우지 말고
          값을 모르는 것으로 다뤄야 합니다. `performance_ids`로 지정한 공연이 응답에 없는 것도 같은 뜻입니다.
          """)
  @GetMapping("/seat-counts")
  public ResponseEntity<ApiResponse<List<SeatStatusCountsByPerformanceResponse>>> getAllSeatCounts(
      // 상한은 호출 측 페이지 상한(공유 상수 MAX_PAGE_SIZE, 현재 50)보다 넉넉하게 잡는다. 같은 값으로 묶으면
      // 누가 공유 상수를 올리는 순간 이 경로만 400이 되고, 그 400은 호출 측 fail-open이 흡수해 점유율 컬럼이
      // 전부 비는 형태로만 드러난다 — 원인 추적이 로그 한 줄에 달린다. user-service도 같은 이유로 여유를 뒀다.
      @RequestParam(required = false) @Size(min = 1, max = 120) List<Long> performanceIds) {
    return ApiResponse.onSuccess(
        SuccessStatus.OK, seatFacade.getAllPerformanceSeatStatusCounts(performanceIds));
  }

  @Operation(summary = "좌석 판매 확정", description = "HOLD 상태 좌석을 SOLD 상태로 확정합니다.")
  @PostMapping("/sold")
  public ResponseEntity<ApiResponse<Void>> confirmSold(
      @Valid @RequestBody SeatSoldConfirmRequest request) {
    seatFacade.confirmSold(request.bookingNumber(), request.seatId());
    return ApiResponse.onSuccess(SuccessStatus.OK);
  }

  @Operation(
      summary = "좌석 선점 즉시 반납",
      description =
          """
          PENDING 예매가 즉시 취소돼 HOLD 좌석을 만료 전에 AVAILABLE로 되돌립니다 (#559).

          멱등합니다 — 이미 해제됐거나 다른 예매가 쥔 좌석이면 아무것도 하지 않고 성공을 반환합니다.
          호출자(booking)는 예매 취소를 이미 확정한 뒤에 부르므로, 되돌릴 것이 없기 때문입니다.
          """)
  @PostMapping("/release")
  public ResponseEntity<ApiResponse<Void>> releaseHold(
      @Valid @RequestBody SeatHoldReleaseRequest request) {
    seatFacade.releaseHold(request.bookingNumber(), request.seatId());
    return ApiResponse.onSuccess(SuccessStatus.OK);
  }
}
