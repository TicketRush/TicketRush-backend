package com.ticketrush.boundedcontext.seat.in.api.v1;

import com.ticketrush.boundedcontext.seat.app.dto.request.SeatSoldConfirmRequest;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatNumberResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsResponse;
import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/seat")
@RequiredArgsConstructor
public class SeatController {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private final SeatFacade seatFacade;

  @Value("${gateway.internal-token}")
  private String internalToken;

  @GetMapping("/{performanceId}/seat-layouts")
  @Operation(summary = "공연별 좌석 배치 조회", description = "공연 ID에 해당하는 좌석 ID, 좌석 배치 ID, 좌석 번호를 조회합니다.")
  public ResponseEntity<ApiResponse<List<SeatLayoutResponse>>> getSeatLayouts(
      @PathVariable Long performanceId) {
    List<SeatLayoutResponse> response = seatFacade.getPerformanceSeatLayouts(performanceId);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @GetMapping("/numbers")
  @Operation(summary = "좌석 번호 목록 조회", description = "좌석 ID 목록에 해당하는 좌석 번호를 조회합니다.")
  public ResponseEntity<ApiResponse<List<SeatNumberResponse>>> getSeatNumbers(
      @RequestParam List<Long> seatIds) {
    List<SeatNumberResponse> response = seatFacade.getSeatNumbers(seatIds);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @GetMapping("/{performanceId}/seat-counts")
  @Operation(
      summary = "공연 좌석 상태별 수 조회",
      description = "공연 ID로 전체, 예매 가능, 판매 완료, 임시 선점 좌석 수를 조회합니다.")
  @SeatCountsApiResponses
  public ResponseEntity<ApiResponse<SeatStatusCountsResponse>> getSeatCounts(
      @Parameter(description = "공연 ID") @PathVariable Long performanceId) {
    SeatStatusCountsResponse response = seatFacade.getPerformanceSeatStatusCounts(performanceId);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @GetMapping(
      value = "/{performanceId}/seat-status/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(summary = "공연별 좌석 상태 SSE 구독", description = "공연 ID에 해당하는 좌석 상태 변경 이벤트를 실시간으로 구독합니다.")
  public SseEmitter subscribeSeatStatus(@PathVariable Long performanceId) {
    return seatFacade.subscribeSeatStatus(performanceId);
  }

  @PostMapping("/internal/sold")
  @Operation(summary = "좌석 판매 확정", description = "내부 토큰을 검증한 뒤 HOLD 상태 좌석을 SOLD 상태로 확정합니다.")
  public ResponseEntity<ApiResponse<Void>> confirmSold(
      @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String internalTokenHeader,
      @Valid @RequestBody SeatSoldConfirmRequest request) {
    validateInternalToken(internalTokenHeader);
    seatFacade.confirmSold(request.bookingNumber(), request.seatId());
    return ApiResponse.onSuccess(SuccessStatus.OK);
  }

  private void validateInternalToken(String internalTokenHeader) {
    if (!internalToken.equals(internalTokenHeader)) {
      throw new BusinessException(ErrorStatus.FORBIDDEN);
    }
  }
}
