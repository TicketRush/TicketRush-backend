package com.ticketrush.boundedcontext.seat.in.api.v1;

import com.ticketrush.boundedcontext.seat.app.dto.request.SeatSoldConfirmRequest;
import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden // 내부 전용 API — 공개 OpenAPI 문서(/v3/api-docs)에 노출하지 않는다.
@Tag(name = "SeatInternal", description = "좌석 내부 통신 API")
@RestController
@RequestMapping("/api/v1/internal/seat")
@RequiredArgsConstructor
public class SeatInternalController {

  private final SeatFacade seatFacade;

  @Operation(summary = "좌석 판매 확정", description = "HOLD 상태 좌석을 SOLD 상태로 확정합니다.")
  @PostMapping("/sold")
  public ResponseEntity<ApiResponse<Void>> confirmSold(
      @Valid @RequestBody SeatSoldConfirmRequest request) {
    seatFacade.confirmSold(request.bookingNumber(), request.seatId());
    return ApiResponse.onSuccess(SuccessStatus.OK);
  }
}
