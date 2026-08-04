package com.ticketrush.boundedcontext.booking.app.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 예매 요약 통계 응답 DTO")
public record BookingAdminStatsResponse(
    @Schema(description = "전체 예매 수. 상태 무관이며 관리자 목록 조회의 total_elements와 같다.", example = "1250")
        long totalBookings,
    @Schema(description = "완료된 예매 수(CONFIRMED).", example = "980") long completedBookings,
    @Schema(
            description =
                "취소된 예매 수(CANCELED + REFUNDED). 결제하지 않아 자동 만료된 EXPIRED는 포함하지 않는다. "
                    + "네 지표는 서로 배타적 분할이 아니다 — PENDING·REFUNDING·EXPIRED는 완료에도 취소에도 잡히지 않는다.",
            example = "120")
        long canceledBookings,
    @Schema(
            description =
                "총 매출. 완료된 예매의 실제 결제 금액 합이다. 예매 확정 시점의 금액을 예매가 직접 보유하므로 "
                    + "이후 공연 가격이 바뀌거나 공연이 삭제돼도 이 값은 흔들리지 않는다. "
                    + "`revenue_complete`가 false면 실제 매출보다 작은 값이다.",
            example = "147000000")
        long totalRevenue,
    @Schema(
            description =
                "총 매출이 완전한지 여부. 결제 금액이 기록되지 않은 확정 예매가 하나라도 있으면 false이며, "
                    + "그 건수는 `missing_amount_bookings`다. 결제 금액 컬럼 도입 이전에 확정된 예매가 백필되지 않은 경우에만 "
                    + "false가 되고, 정상 운영 중에는 항상 true다.",
            example = "true")
        boolean revenueComplete,
    @Schema(description = "결제 금액이 비어 있어 매출에 반영되지 못한 확정 예매 수. 0이면 총 매출이 완전하다.", example = "0")
        long missingAmountBookings) {

  public static BookingAdminStatsResponse from(BookingStatsCounts counts) {
    return new BookingAdminStatsResponse(
        counts.totalCount(),
        counts.confirmedCount(),
        counts.canceledCount(),
        counts.confirmedRevenue(),
        counts.amountMissingCount() == 0,
        counts.amountMissingCount());
  }
}
