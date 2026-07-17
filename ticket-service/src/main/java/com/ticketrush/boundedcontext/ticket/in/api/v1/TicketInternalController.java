package com.ticketrush.boundedcontext.ticket.in.api.v1;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketInternalResponse;
import com.ticketrush.boundedcontext.ticket.app.usecase.TicketGetInternalUseCase;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden // 내부 전용 API — 공개 OpenAPI 문서(/v3/api-docs)에 노출하지 않는다.
@Tag(name = "TicketInternal", description = "입장권 내부 통신 API")
@RestController
@RequestMapping("/api/v1/internal/ticket")
@RequiredArgsConstructor
public class TicketInternalController {

  private final TicketGetInternalUseCase ticketGetInternalUseCase;

  @Operation(summary = "입장권 상태 내부 조회", description = "예매에 발급된 입장권의 상태를 반환합니다.")
  @GetMapping("/bookings/{bookingId}")
  public ResponseEntity<ApiResponse<TicketInternalResponse>> getTicketInternal(
      @PathVariable Long bookingId) {
    TicketInternalResponse response = ticketGetInternalUseCase.execute(bookingId);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }
}
