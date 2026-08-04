package com.ticketrush.boundedcontext.booking.app.dto.response;

import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceInfoResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

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
                "총 매출. 완료된 예매의 공연 가격 합이며, 1인 1매라 예매 수 × 공연 가격이다. "
                    + "**공연 가격 조회가 하나라도 완결되지 않으면 null이다** — 일부만 더한 값은 매출 감소로 오독되므로 내리지 않는다. "
                    + "완료된 예매가 0건이면 null이 아니라 0이다. "
                    + "출처가 현재 공연 가격이므로 예매 후 관리자가 가격을 바꾸면 이 값도 함께 바뀐다 — "
                    + "실제 결제액의 SSOT는 payment-service다.",
            example = "147000000")
        Long totalRevenue) {

  /**
   * 공연별 집계 행과 공연 가격을 합쳐 통계를 만든다 (#561).
   *
   * <p>카운트 3종은 DB만으로 확정되므로 항상 정확하다. 매출만 원격 조회에 의존하는데, {@code PerformanceRestClient}는 실패·예산초과 건을 맵에서
   * 조용히 빼므로 그대로 더하면 <b>축소된 매출이 정상값처럼 보인다</b>. 그래서 기여해야 할 공연이 하나라도 빠지면 부분 합 대신 null을 내린다.
   */
  public static BookingAdminStatsResponse of(
      List<BookingPerformanceStatsRow> rows, Map<Long, PerformanceInfoResponse> performances) {
    long totalBookings = 0;
    long completedBookings = 0;
    long canceledBookings = 0;
    long revenue = 0;
    boolean revenueResolved = true;

    for (BookingPerformanceStatsRow row : rows) {
      totalBookings += row.totalCount();
      completedBookings += row.confirmedCount();
      canceledBookings += row.canceledCount();

      if (row.confirmedCount() == 0) {
        // 매출에 기여하지 않으므로 가격을 몰라도 집계가 흔들리지 않는다.
        continue;
      }

      PerformanceInfoResponse performance = performances.get(row.performanceId());
      if (performance == null || performance.price() == null) {
        revenueResolved = false;
        continue;
      }
      revenue += row.confirmedCount() * performance.price();
    }

    return new BookingAdminStatsResponse(
        totalBookings, completedBookings, canceledBookings, revenueResolved ? revenue : null);
  }
}
