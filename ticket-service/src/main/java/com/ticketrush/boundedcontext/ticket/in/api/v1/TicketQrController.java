package com.ticketrush.boundedcontext.ticket.in.api.v1;

import com.ticketrush.boundedcontext.ticket.app.dto.response.TicketQrResponse;
import com.ticketrush.boundedcontext.ticket.app.usecase.TicketQrGetUseCase;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.security.CustomUserDetails;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Ticket", description = "입장권 관련 API")
@RestController
@RequestMapping("/api/v1/ticket")
@RequiredArgsConstructor
public class TicketQrController {

  private final TicketQrGetUseCase ticketQrGetUseCase;

  @Operation(
      summary = "입장권 QR payload 조회",
      description = "로그인 사용자 본인의 확정된 예매에 한해 QR로 렌더링할 payload와 입장권 메타데이터를 반환합니다.")
  @GetMapping("/bookings/{bookingId}/qr")
  public ResponseEntity<ApiResponse<TicketQrResponse>> getTicketQr(
      @AuthenticationPrincipal CustomUserDetails user, @PathVariable Long bookingId) {
    TicketQrResponse response = ticketQrGetUseCase.execute(user.getUserId(), bookingId);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }
}
