package com.ticketrush.boundedcontext.seat.in.api.v1;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAdminMonitoringResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAdminSeatDetailResponse;
import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.dto.response.ApiResponse;
import com.ticketrush.global.security.CustomUserDetails;
import com.ticketrush.global.status.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seat Admin", description = "좌석 관리자 API")
@Validated
@RestController
@RequestMapping("/api/v1/seat/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SeatAdminController {

  private final SeatFacade seatFacade;

  @Operation(
      summary = "공연 좌석 현황 모니터링",
      description =
          """
          공연의 좌석 맵과 상태 요약(전체·예약 가능·판매 완료·예약 진행)을 한 번에 조회합니다.

          **응답은 항상 조회 시점의 DB 상태입니다.** 공개 좌석맵 API가 쓰는 30초 캐시를 경유하지 않습니다 — 관리자가 방금
          강제 해제한 좌석이 잠시 예약 진행 중으로 보이면 안 되기 때문입니다. 화면 갱신은 새로고침·관리자 작업·좌석 선택
          시점에 이 API를 다시 호출하는 방식이며, 실시간 구독(SSE)은 제공하지 않습니다.

          요약의 집계 규칙은 공개 `seat-counts` API와 같습니다 — 만료 시각이 지난 예약 진행 좌석은 아직 해제 처리 전이라도
          예약 가능으로 셉니다.
          """)
  @GetMapping("/{performanceId}/monitoring")
  public ResponseEntity<ApiResponse<SeatAdminMonitoringResponse>> getMonitoring(
      @Parameter(description = "공연 ID") @PathVariable Long performanceId) {
    SeatAdminMonitoringResponse response = seatFacade.getAdminMonitoring(performanceId);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "좌석 단건 상세 조회",
      description =
          """
          좌석 번호, 좌석 상태, 예매 번호, 예약 시작 시간, 예약 만료 시각, 남은 예약 시간을 조회합니다.

          **예약자 이름·이메일은 이 응답에 없습니다.** 좌석은 예매 번호만 보유하고 회원 정보를 알지 못하며, 좌석 도메인이
          예매자 개인정보를 조회하지 않도록 경계를 유지합니다. 예매자 정보가 필요하면 응답의 `booking_number`로
          `GET /api/v1/booking/admin/bookings/{bookingNumber}`를 호출하십시오.

          `hold_started_at`은 저장된 값이 아니라 `hold_expired_at`에서 선점 유지 시간(5분)을 뺀 값입니다.
          `remaining_seconds`는 이미 만료됐거나 예약 진행 중이 아니면 0입니다.

          경로의 공연에 속하지 않는 좌석 ID는 `SEAT_404_001`로 거절합니다.
          """)
  @GetMapping("/{performanceId}/{seatId}")
  public ResponseEntity<ApiResponse<SeatAdminSeatDetailResponse>> getSeatDetail(
      @Parameter(description = "공연 ID") @PathVariable Long performanceId,
      @Parameter(description = "좌석 ID") @PathVariable Long seatId) {
    SeatAdminSeatDetailResponse response = seatFacade.getAdminSeatDetail(performanceId, seatId);

    return ApiResponse.onSuccess(SuccessStatus.OK, response);
  }

  @Operation(
      summary = "예약 강제 해제",
      description =
          """
          예약 진행 중(HOLD)인 좌석의 선점을 만료 전에 해제합니다. 응답 시점에 좌석은 이미 예약 가능 상태이며
          `seat-counts` 집계에도 즉시 반영됩니다. 처리자(`adminId`)와 대상은 ADMIN-AUDIT 로그로 남습니다.

          **해당 예매는 만료(EXPIRED)로 종결됩니다.** 좌석 반납 이벤트를 booking-service가 받아 결제 대기 중이던 예매를
          만료 처리하며, 이후 그 예매로는 결제가 완료될 수 없습니다. 사용자가 스스로 취소한 경우(CANCELED)와 구분되며,
          예매 상태 반영은 이벤트 전달 직후이므로 예매 목록에서 확인하십시오.

          예약 진행 중이 아닌 좌석(예약 가능·판매 완료)은 `SEAT_409_004`로 거절합니다. 요청 처리 중에 결제가 확정되거나
          다른 예매가 좌석을 재선점한 경우도 같은 코드로 거절합니다 — 해제되지 않았다는 뜻입니다.

          **판매 완료 좌석은 이 API의 대상이 아닙니다.** 결제된 좌석을 되돌리는 것은 환불이며
          `POST /api/v1/booking/admin/{bookingNumber}/refund`가 담당합니다. 좌석은 환불이 성공한 뒤 자동으로 반환됩니다.
          """)
  @DeleteMapping("/{performanceId}/{seatId}/hold")
  public ResponseEntity<ApiResponse<Void>> forceReleaseHold(
      @AuthenticationPrincipal CustomUserDetails admin,
      @Parameter(description = "공연 ID") @PathVariable Long performanceId,
      @Parameter(description = "좌석 ID") @PathVariable Long seatId) {
    seatFacade.forceReleaseHold(admin.getUserId(), performanceId, seatId);

    return ApiResponse.onSuccess(SuccessStatus.OK);
  }
}
