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
      description =
          "로그인 사용자 본인의 취소되지 않은 예매에 한해 QR로 렌더링할 payload와 입장권 메타데이터를 반환합니다. "
              + "환불 진행 중(REFUNDING)인 예매도 payload를 반환하므로, "
              + "조회 성공을 입장 가능으로 해석하면 안 됩니다. 입장 가능 여부는 입장 검증 API가 최종 판정합니다.")
  @GetMapping("/bookings/{bookingId}/qr")
  public ResponseEntity<ApiResponse<TicketQrResponse>> getTicketQr(
      @AuthenticationPrincipal CustomUserDetails user, @PathVariable Long bookingId) {
    TicketQrResponse response = ticketQrGetUseCase.execute(user.getUserId(), bookingId);
    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }
}
