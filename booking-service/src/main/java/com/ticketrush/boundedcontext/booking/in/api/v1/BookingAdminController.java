package com.ticketrush.boundedcontext.booking.in.api.v1;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminSummaryResponse;
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
      summary = "전체 예매 목록 조회",
      description =
          """
          상태 무관 전체 예매를 최신순으로 페이징 조회합니다. 검색·다중조건 필터는 제공하지 않습니다.

          공연 이름·날짜, 예매자 이름·이메일, 좌석 번호, 결제 금액은 각각 performance·user·seat-service에서 보강합니다.
          **해당 서비스 장애 시 그 필드만 null로 내려가고 목록 자체는 성공합니다** — 프론트는 `performance_id`·`user_id`·`seat_id`로
          재조회할 수 있습니다.

          행 확장 드롭다운에 필요한 필드(예매자 정보·좌석 정보·좌석 수·티켓 단가·총 결제 금액)도 이 응답에 함께 실려 있어 별도 상세 조회가
          필요 없습니다. 1인 1매라 좌석 수는 항상 1이고 티켓 단가와 총 결제 금액이 같은 값이므로 `payment_amount` 하나로 내립니다.
          """)
  @GetMapping("/bookings")
  public ResponseEntity<ApiResponse<List<BookingAdminSummaryResponse>>> getBookings(
      @ModelAttribute OffsetPageRequest pageRequest) {
    Page<BookingAdminSummaryResponse> response = bookingFacade.getAdminBookings(pageRequest);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "예매 요약 통계 조회",
      description =
          """
          전체 예매 수(상태 무관), 완료된 예매 수(CONFIRMED), 취소된 예매 수(CANCELED + REFUNDED), 총 매출을 조회합니다.

          **네 지표는 서로 배타적 분할이 아닙니다.** 결제하지 않아 자동 만료된 EXPIRED, 결제 대기 중인 PENDING, 환불 진행 중인 REFUNDING은
          완료에도 취소에도 잡히지 않습니다.

          총 매출은 완료된 예매의 공연 가격 합입니다(1인 1매라 예매 수 × 공연 가격). **공연 가격 조회가 하나라도 완결되지 않으면 `null`입니다** —
          일부만 더한 값은 매출 감소로 오독되므로 내리지 않습니다. 완료된 예매가 0건이면 `null`이 아니라 `0`입니다.
          """)
  @GetMapping("/bookings/stats")
  public ResponseEntity<ApiResponse<BookingAdminStatsResponse>> getBookingStats() {
    BookingAdminStatsResponse response = bookingFacade.getAdminBookingStats();

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "환불 처리",
      description =
          """
          관리자가 결제 완료(CONFIRMED) 예매를 환불 처리합니다. 예매를 REFUNDING으로 전환하고 환불 요청 이벤트를 발행하며,
          처리자(`adminId`)와 대상은 ADMIN-AUDIT 로그로 남습니다.

          **응답 시점의 상태는 REFUNDING입니다.** 환불 완료(REFUNDED)와 좌석 반환은 PG 환불 성공 이벤트가 도착한 뒤이며(refund-first),
          목록을 다시 조회해 확인합니다.

          CONFIRMED가 아닌 예매는 `BOOKING_409_001`로 거절합니다. 이미 입장을 완료한 예매도 환불할 수 없습니다(`BOOKING_409_006`) —
          착석한 좌석이 재판매되는 것을 막기 위한 정책이며 사용자 취소 경로와 동일하게 적용됩니다.

          환불에 **실패한 이력이 있는 건의 재시도**와 REFUNDING 고착 복구는 이 API가 아니라 환불 재시도 API가 담당합니다.
          """)
  @PostMapping("/{bookingNumber}/refund")
  public ResponseEntity<ApiResponse<Void>> refundBooking(
      @AuthenticationPrincipal CustomUserDetails admin,
      @Parameter(description = "예매 번호") @PathVariable String bookingNumber) {
    bookingFacade.refundBooking(admin.getUserId(), bookingNumber);

    return ApiResponse.onSuccess(SuccessStatus.OK);
  }

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
      summary = "환불 고착 예매 조회",
      description =
          """
          환불 진행 중(REFUNDING) 상태에서 임계 시간(기본 30분) 이상 멈춰 있는 예매를 오래된 순으로 조회합니다.

          PG 통신 실패가 재시도 끝에 DLT로 빠지면 환불 종결 이벤트가 오지 않아 예매가 REFUNDING에 영구히
          머뭅니다 — 돈을 돌려받지 못한 채 좌석은 SOLD로 묶이고 입장도 차단됩니다. 이 조회로 식별한 예매는
          환불 재시도 API로 환불 요청 이벤트를 재발행해 복구할 수 있습니다. `updatedAt`이 REFUNDING에
          진입한 시각입니다.

          **재시도를 눌러도 이 목록에서 즉시 사라지지 않습니다.** 재발행은 예매를 변경하지 않으므로, 환불
          종결 이벤트가 실제로 도착해 상태가 REFUNDED(또는 CONFIRMED)로 바뀔 때 목록에서 빠집니다.
          즉 목록에 남아 있다는 것은 아직 미해결이라는 뜻입니다. 이미 재시도를 눌렀는지는 ADMIN-AUDIT
          로그로 확인하세요.
          """)
  @GetMapping("/bookings/refunding-stuck")
  public ResponseEntity<ApiResponse<List<BookingSummaryResponse>>> getRefundingStuckBookings(
      @ModelAttribute OffsetPageRequest pageRequest) {
    Page<BookingSummaryResponse> response = bookingFacade.getRefundingStuckBookings(pageRequest);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "환불 재시도",
      description =
          """
          환불에 실패한 예매의 환불을 다시 시도합니다. 예매를 REFUNDING으로 전환하고 환불 요청 이벤트를 발행합니다.

          **대상은 환불 실패 이력이 있는 CONFIRMED 예매, 그리고 REFUNDING에서 임계 시간(기본 30분) 이상
          멈춘 고착 예매입니다.** 고착 예매는 이미 REFUNDING이므로 상태 전이 없이 환불 요청 이벤트만
          재발행합니다. 그 외(실패 이력이 없는 정상 예매, 정상 진행 중인 REFUNDING, 이미 종결된 예매)는
          `BOOKING_409_005`로 거절합니다.

          고착 건에 대한 재발행은 예매를 변경하지 않으므로 반복 호출해도 막히지 않습니다. 그만큼 PG 호출이
          나가지만 payment가 재수신에 멱등이라 이중 환불은 발생하지 않습니다. 불필요한 재호출을 피하려면
          ADMIN-AUDIT 로그로 이전 시도 여부를 먼저 확인하세요.

          결제가 아직 완료 상태면 PG 환불을 재실행하고, 결제 취소 API로 이미 우회 환불된 상태면 결제 취소 이벤트를
          재발행해 예매·좌석 정합을 회복합니다. 사용자도 예매를 다시 취소해 재환불할 수 있으며, 이 API는 CS가 사용자를
          대신해 시도하기 위한 것입니다.

          이미 입장을 완료한(입장권 사용됨) 예매는 관리자도 재환불할 수 없습니다(409 `BOOKING_409_006`).
          착석한 좌석이 재판매되는 것을 막기 위한 정책이며, 사용자 취소 경로와 동일하게 적용됩니다.
          """)
  @PostMapping("/{bookingNumber}/refund-retry")
  public ResponseEntity<ApiResponse<Void>> retryRefund(
      @AuthenticationPrincipal CustomUserDetails admin,
      @Parameter(description = "예매 번호") @PathVariable String bookingNumber) {
    bookingFacade.retryRefund(admin.getUserId(), bookingNumber);

    return ApiResponse.onSuccess(SuccessStatus.OK);
  }
}
