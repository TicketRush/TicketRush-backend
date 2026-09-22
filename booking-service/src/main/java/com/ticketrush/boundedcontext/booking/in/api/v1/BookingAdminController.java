package com.ticketrush.boundedcontext.booking.in.api.v1;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminRefundSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingRefundStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.app.facade.BookingFacade;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.domain.types.RefundProcessStatus;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.security.CustomUserDetails;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.springframework.web.bind.annotation.RequestParam;
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
          전체 예매를 최신순으로 페이징 조회합니다. 검색·다중조건 필터는 제공하지 않습니다.

          `status`를 반복하면 선택한 상태의 합집합을 필터링해 페이징합니다. 예: 대기·환불 중은
          `?status=PENDING&status=REFUNDING`, 운영 화면의 전체 탭은
          `?status=CONFIRMED&status=PENDING&status=REFUNDING&status=REFUNDED`입니다.
          단일 `?status=REFUNDED`도 그대로 지원하며, **미지정 시 상태 무관 전체를 조회합니다.**
          필터를 DB 조회에 적용한 뒤 전역 `id DESC`로 정렬·페이징하므로 상태별 페이지를 합칠 때 생기는 누락이 없습니다.

          **값은 대소문자를 구분합니다**(`REFUNDED` O, `refunded` X). 여러 값 중 하나라도 정의되지 않은 값이면 400으로
          거절합니다. 중복 값은 한 번만 적용합니다. `status=`처럼 빈 값은 무시하므로, 모든 값이 비면 미지정과 같은 전체 조회이고
          `?status=&status=PENDING`이면 PENDING만 조회합니다.

          공연 이름·날짜, 예매자 이름·이메일, 좌석 번호는 각각 performance·user·seat-service에서 보강합니다.
          **해당 서비스 장애 시 그 필드만 null로 내려가고 목록 자체는 성공합니다** — 프론트는 `performance_id`·`user_id`·`seat_id`로
          재조회할 수 있습니다.

          **결제 금액은 보강 대상이 아닙니다.** 예매가 확정 시점의 실제 결제액을 직접 보유하므로 다른 서비스의 장애나 공연 가격 변경에
          영향받지 않습니다. 아직 결제되지 않은 예매(PENDING 등)만 null입니다.

          행 확장 드롭다운에 필요한 필드(예매자 정보·좌석 정보·좌석 수·티켓 단가·총 결제 금액)도 이 응답에 함께 실려 있어 별도 상세 조회가
          필요 없습니다. 1인 1매라 좌석 수는 항상 1이고 티켓 단가와 총 결제 금액이 같은 값이므로 `payment_amount` 하나로 내립니다.

          예매자 개인정보가 포함되므로 조회 사실이 ADMIN-AUDIT 로그에 남습니다(조회자와 건수만 기록하며 응답 내용은 남기지 않습니다).
          """)
  @GetMapping("/bookings")
  public ResponseEntity<ApiResponse<List<BookingAdminSummaryResponse>>> getBookings(
      @AuthenticationPrincipal CustomUserDetails admin,
      @Parameter(description = "예매 상태 필터(반복 가능: status=PENDING&status=REFUNDING; 미지정·빈 값은 전체)")
          @RequestParam(name = "status", required = false)
          List<BookingStatus> statuses,
      @ModelAttribute OffsetPageRequest pageRequest) {
    Page<BookingAdminSummaryResponse> response =
        bookingFacade.getAdminBookings(admin.getUserId(), normalizeStatuses(statuses), pageRequest);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  private Set<BookingStatus> normalizeStatuses(List<BookingStatus> statuses) {
    if (statuses == null) {
      return Set.of();
    }

    return statuses.stream()
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  @Operation(
      summary = "예매 단건 조회",
      description =
          """
          예매 번호로 예매 1건을 조회합니다. 응답 형태는 목록과 동일합니다 — 1인 1매라 목록 필드가 곧 상세 필드입니다.

          **좌석 관리자 화면(#562)이 이 API로 예매자를 조합합니다.** 좌석 조회 API는 좌석의 `booking_number`까지만 내리고
          예매자 이름·이메일은 내리지 않습니다. 좌석 도메인이 예매자 정보를 보유하지도, 조회하지도 않게 하려는 경계이며,
          프론트가 좌석 상세의 `booking_number`로 이 API를 호출해 두 화면 데이터를 합칩니다.

          공연 이름·날짜, 예매자 이름·이메일, 좌석 번호는 각각 performance·user·seat-service에서 보강합니다.
          **해당 서비스 장애 시 그 필드만 null로 내려가고 조회 자체는 성공합니다** — 프론트는 `performance_id`·`user_id`·`seat_id`로
          재조회할 수 있습니다.

          존재하지 않는 예매 번호는 `BOOKING_404_001`로 거절합니다.

          예매자 개인정보가 포함되므로 조회 사실이 ADMIN-AUDIT 로그에 남습니다(조회자와 예매 번호만 기록하며 응답 내용은 남기지 않습니다).
          """)
  @GetMapping("/bookings/{bookingNumber}")
  public ResponseEntity<ApiResponse<BookingAdminSummaryResponse>> getBooking(
      @AuthenticationPrincipal CustomUserDetails admin,
      @Parameter(description = "예매 번호") @PathVariable String bookingNumber) {
    BookingAdminSummaryResponse response =
        bookingFacade.getAdminBooking(admin.getUserId(), bookingNumber);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "예매 요약 통계 조회",
      description =
          """
          전체 예매 수(상태 무관), 완료된 예매 수(CONFIRMED), 취소된 예매 수(CANCELED + REFUNDED), 총 매출을 조회합니다.

          **네 지표는 서로 배타적 분할이 아닙니다.** 결제하지 않아 자동 만료된 EXPIRED, 결제 대기 중인 PENDING, 환불 진행 중인 REFUNDING은
          완료에도 취소에도 잡히지 않습니다.

          총 매출은 완료된 예매의 **실제 결제 금액** 합입니다. 예매가 확정 시점의 금액을 직접 보유하므로 공연 가격이 바뀌거나
          공연이 삭제돼도 흔들리지 않고, 이 API는 다른 서비스를 호출하지 않습니다.

          결제 금액이 기록되지 않은 확정 예매가 있으면 그만큼 매출이 작게 나옵니다. 이때 `revenue_complete`가 false가 되고
          `missing_amount_bookings`에 그 건수가 담깁니다 — 축소된 매출을 정상값으로 오해하지 않도록 응답이 스스로 알립니다.
          컬럼 도입 이전에 확정된 예매가 백필되지 않은 경우에만 해당하며, 정상 운영 중에는 항상 true입니다.
          """)
  @GetMapping("/bookings/stats")
  public ResponseEntity<ApiResponse<BookingAdminStatsResponse>> getBookingStats() {
    BookingAdminStatsResponse response = bookingFacade.getAdminBookingStats();

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "관리자 환불 통합 목록 조회",
      description =
          """
          환불 진행 중·환불 완료·미해결 환불 실패 예매를 하나의 목록으로 최신순 페이징 조회합니다.

          **환불 대상은 세 조건의 합집합입니다.** 예매 상태가 `REFUNDING`이면 진행 중, `REFUNDED`면 완료,
          `CONFIRMED`이면서 환불 실패 이력이 있으면 미해결 실패입니다. 정상 확정 예매, 결제 전 취소(`CANCELED`),
          미결제 만료(`EXPIRED`)는 환불이 아니므로 제외됩니다.

          진행 중에는 **정상 진행 건과 고착 건이 모두 포함됩니다** — 30분 이상 멈춘 건만 보려면
          `GET /bookings/refunding-stuck`을 그대로 쓰세요. 이 API는 그 조회를 대체하지 않습니다.

          `refund_status`로 처리 상태 하나를 지정하면 그 상태만 필터링해 페이징합니다.
          **미지정 시 세 상태 전체를 조회합니다** — 필터가 페이징보다 앞에 걸리므로 상태별 페이지를 합칠 때
          생기는 누락이 없고, 목록과 `pagination_info`가 같은 모집단을 씁니다. 값은 대소문자를 구분하며
          정의되지 않은 값은 400으로 거절합니다.

          **`refund_status`가 행 분류의 유일한 기준입니다.** `refund_failed_at`은 재환불 시 지워지지 않는
          이력이라 진행 중·완료 행에도 값이 남아 있을 수 있습니다. 그 값으로 분류하면 재시도 중인 예매가
          실패로 잡힙니다. 반대로 한 예매는 예매 상태로 갈리는 한 분기에만 걸리므로 재시도 건이 중복되지 않습니다.

          **기준은 환불 결과 이벤트가 예매에 반영된 시점입니다.** 환불은 비동기라 PG에서 이미 환불이 끝났어도
          완료 이벤트가 도착하기 전이면 아직 진행 중으로 보입니다. 잠시 후 다시 조회하면 상태가 넘어갑니다.

          공연 이름·날짜·시각과 예매자 이름은 performance·user-service에서 보강합니다.
          **해당 서비스 장애 시 그 필드만 null로 내려가고 목록 자체는 성공합니다** — 프론트는 `performance_id`·`user_id`로
          재조회할 수 있습니다. 좌석 번호는 이 화면에서 쓰지 않으므로 내리지 않습니다.

          `payment_amount`는 예매가 확정 시점에 보유한 **실제 결제 금액**이며 **PG에서 확정된 실제 환불액이 아닙니다.**
          결제 금액이 기록되지 않은 예매는 null이고, 그 경우에도 행은 그대로 내려갑니다.

          **시각 규약이 두 가지입니다.** `booked_at`·`refund_failed_at`은 UTC이고,
          `performance_date`·`performance_time`은 Asia/Seoul 벽시계입니다.

          예매자 개인정보가 포함되므로 조회 사실이 ADMIN-AUDIT 로그에 남습니다(조회자·조건·건수만 기록하며 응답 내용은 남기지 않습니다).
          """)
  @GetMapping("/refunds")
  public ResponseEntity<ApiResponse<List<BookingAdminRefundSummaryResponse>>> getRefunds(
      @AuthenticationPrincipal CustomUserDetails admin,
      @Parameter(description = "환불 처리 상태 필터(IN_PROGRESS·COMPLETED·FAILED; 미지정 시 전체)")
          @RequestParam(name = "refund_status", required = false)
          RefundProcessStatus refundStatus,
      @ModelAttribute OffsetPageRequest pageRequest) {
    Page<BookingAdminRefundSummaryResponse> response =
        bookingFacade.getAdminRefunds(admin.getUserId(), refundStatus, pageRequest);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "관리자 환불 요약 통계 조회",
      description =
          """
          전체 환불 건수와 처리 상태별 건수(진행 중·완료·미해결 실패)를 조회합니다.

          **모집단은 환불 목록과 같습니다** — 환불 진행 중(`REFUNDING`) + 환불 완료(`REFUNDED`) +
          미해결 실패(`CONFIRMED`이면서 환불 실패 이력 보유). 결제 전 취소(`CANCELED`)와 미결제 만료(`EXPIRED`)는
          환불이 아니므로 세지 않습니다. 예매 요약 통계(`GET /bookings/stats`)의 `canceled_bookings`는
          `CANCELED + REFUNDED`라 환불 집계가 아니므로 이 값과 다릅니다.

          **네 지표는 서로 배타적이며 `total_refunds`는 나머지 셋의 합입니다.** 같은 모집단을 예매 상태로 나눈
          것이라 합이 어긋날 수 없습니다. 예매 요약 통계의 네 지표가 배타적 분할이 아닌 것과 다릅니다.

          **목록의 `refund_status` 필터는 이 집계에 적용되지 않습니다.** 카드는 필터와 무관하게 항상 전체 모집단을
          기준으로 하며, `refund_status`로 거른 목록의 `pagination_info.total_elements`는 여기의 해당 지표와 같은 값입니다.

          다른 서비스를 호출하지 않으며, 예매가 환불 결과를 이미 보유하므로 네 값 모두 DB 집계 한 번에서 나옵니다.
          환불 결과 이벤트가 반영되기 전의 건은 아직 진행 중으로 잡힙니다.
          """)
  @GetMapping("/refunds/stats")
  public ResponseEntity<ApiResponse<BookingRefundStatsResponse>> getRefundStats() {
    BookingRefundStatsResponse response = bookingFacade.getAdminRefundStats();

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

          사용자 취소가 실패한 건 외에, **좌석 확정 실패로 시스템이 건 보상 환불이 실패한 건**도 여기에 잡힙니다(#492).
          후자는 REFUNDING을 거치지 않으므로 상태 복원 없이 실패 시각만 기록됩니다 — 사용자가 취소를 요청한 적이
          없는데도 목록에 오를 수 있다는 뜻이며, 그 건은 과금이 남아 있으므로 우선 처리 대상입니다.
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
