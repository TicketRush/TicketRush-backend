package com.ticketrush.boundedcontext.seat.in.api.v1;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAvailabilityResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutResponse;
import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seat")
@RequiredArgsConstructor
public class SeatController {

  private final SeatFacade seatFacade;

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
}
