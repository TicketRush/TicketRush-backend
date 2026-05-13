package com.ticketrush.boundedcontext.seat.in.api.v1;

import com.ticketrush.boundedcontext.seat.app.dto.request.SeatSoldConfirmRequest;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAvailabilityResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutResponse;
import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.status.SuccessStatus;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seat")
@RequiredArgsConstructor
public class SeatController {

  private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private final SeatFacade seatFacade;

  @Value("${gateway.internal-token}")
  private String internalToken;

  @GetMapping("/{performanceId}/seat-layouts")
  public ResponseEntity<ApiResponse<List<SeatLayoutResponse>>> getSeatLayouts(
      @PathVariable Long performanceId) {
    List<SeatLayoutResponse> response = seatFacade.getPerformanceSeatLayouts(performanceId);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @GetMapping("/{performanceId}/seat-counts")
  public ResponseEntity<ApiResponse<SeatAvailabilityResponse>> getSeatCounts(
      @PathVariable Long performanceId) {
    SeatAvailabilityResponse response = seatFacade.getPerformanceSeatAvailability(performanceId);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @PostMapping("/internal/sold")
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
