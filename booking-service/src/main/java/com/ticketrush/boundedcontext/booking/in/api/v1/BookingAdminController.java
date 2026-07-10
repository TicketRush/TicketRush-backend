package com.ticketrush.boundedcontext.booking.in.api.v1;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.facade.BookingFacade;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.security.CustomUserDetails;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Booking Admin", description = "예매 관리자 API")
@Validated
@RestController
@RequestMapping("/api/v1/booking/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BookingAdminController {

  private final BookingFacade bookingFacade;

  @Operation(
      summary = "환불 실패 예매 조회",
      description =
          """
          환불에 실패해 아직 해결되지 않은 예매를 조회합니다.

          환불 실패는 별도 상태가 아니라 `booking_status = CONFIRMED` + `refund_failed_at IS NOT NULL`로
          표현됩니다. 환불이 실패하면 취소가 성사되지 않은 것이므로 예매는 유효한 상태(CONFIRMED)로 복원되고,
          실패 사실만 시각으로 남습니다. 실패 사유는 payment-service의 환불 이력이 SSOT입니다.
          """)
  @GetMapping("/bookings/refund-failed")
  public ResponseEntity<ApiResponse<List<BookingSummaryResponse>>> getRefundFailedBookings(
      @ModelAttribute OffsetPageRequest pageRequest) {
    Page<BookingSummaryResponse> response = bookingFacade.getRefundFailedBookings(pageRequest);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "환불 재시도",
      description =
          """
          환불에 실패한 예매의 환불을 다시 시도합니다. 예매를 REFUNDING으로 전환하고 환불 요청 이벤트를 발행합니다.

          **대상은 환불 실패 이력이 있는 CONFIRMED 예매뿐입니다.** 실패 이력이 없는 정상 예매나 이미 환불이
          진행·종결된 예매는 `BOOKING_409_005`로 거절합니다.

          결제가 아직 완료 상태면 PG 환불을 재실행하고, 결제 취소 API로 이미 우회 환불된 상태면 결제 취소 이벤트를
          재발행해 예매·좌석 정합을 회복합니다. 사용자도 예매를 다시 취소해 재환불할 수 있으며, 이 API는 CS가 사용자를
          대신해 시도하기 위한 것입니다.
          """)
  @PostMapping("/{bookingNumber}/refund-retry")
  public ResponseEntity<ApiResponse<Void>> retryRefund(
      @AuthenticationPrincipal CustomUserDetails admin,
      @Parameter(description = "예매 번호") @PathVariable String bookingNumber) {
    bookingFacade.retryRefund(admin.getUserId(), bookingNumber);

    return ApiResponse.onSuccess(SuccessStatus.OK);
  }
}
