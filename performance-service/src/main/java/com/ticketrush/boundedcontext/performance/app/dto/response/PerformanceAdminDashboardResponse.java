package com.ticketrush.boundedcontext.performance.app.dto.response;

import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 대시보드 집계 응답 (#563).
 *
 * <p><b>값을 못 읽은 것과 0은 다르다.</b> 예매·좌석 서비스 조회가 실패하면 그 축의 필드만 비우고 대시보드 자체는 성공한다. 0으로 채우면 관리자가 "매출이
 * 0원"과 "매출을 못 읽었다"를 구분할 수 없다.
 *
 * <p><b>비운 필드는 응답에서 키째 사라진다.</b> 공통 Jackson 설정이 {@code NON_NULL}을 전역 기본값으로 걸어 두어 null 필드는 직렬화되지
 * 않는다. 즉 클라이언트가 보는 것은 {@code "total_revenue": null}이 아니라 <b>키의 부재</b>다 — 각 필드 설명도 그 기준으로 적었다.
 */
@Schema(description = "관리자 대시보드 집계 응답 DTO")
public record PerformanceAdminDashboardResponse(
    @Schema(description = "등록된 공연 수. 삭제된 공연만 제외하며 상태는 가리지 않는다(판매 전·취소 공연도 포함).", example = "42")
        long registeredPerformances,
    @Schema(
            description =
                "판매된 티켓 수. 확정된 예매 수이며 1인 1매라 티켓 수와 같다. " + "예매 서비스 조회에 실패하면 이 필드는 응답에서 생략된다.",
            example = "980",
            nullable = true)
        Long soldTickets,
    @Schema(
            description =
                "총 매출. 확정된 예매의 실제 결제 금액 합이며 전체 기간이다(조회 기간 파라미터의 영향을 받지 않는다). "
                    + "관리자 예매 통계 API의 총 매출과 같은 값이다. 예매 서비스 조회에 실패하면 이 필드는 응답에서 생략된다.",
            example = "147000000",
            nullable = true)
        Long totalRevenue,
    @Schema(
            description =
                "총 매출이 완전한지 여부. 결제 금액이 기록되지 않은 확정 예매가 있으면 false이며 그 건수는 "
                    + "`missing_amount_bookings`다. false면 표시된 매출은 실제보다 작다. "
                    + "예매 서비스 조회에 실패하면 이 필드는 응답에서 생략된다.",
            example = "true",
            nullable = true)
        Boolean revenueComplete,
    @Schema(
            description = "결제 금액이 비어 있어 매출에 반영되지 못한 확정 예매 수. " + "예매 서비스 조회에 실패하면 이 필드는 응답에서 생략된다.",
            example = "0",
            nullable = true)
        Long missingAmountBookings,
    @Schema(
            description =
                "평균 좌석 점유율(0~1, 소수점 넷째 자리 반올림). 판매가 실제로 일어나는 공연(판매중·판매종료)만 모수이며 "
                    + "판매 전·취소 공연은 제외한다. 공연별 점유율의 산술평균이 아니라 "
                    + "**전체 판매 좌석 ÷ 전체 좌석**이라 매출·티켓 수와 같은 축이다. "
                    + "좌석 서비스 조회에 실패하면 이 필드는 응답에서 생략된다.",
            example = "0.317",
            nullable = true)
        Double averageOccupancyRate,
    @Schema(
            description = "평균 점유율의 분자. 모수 공연들의 판매 좌석 합이다. 점유율과 함께 생략된다.",
            example = "118",
            nullable = true)
        Long occupancySoldSeats,
    @Schema(
            description = "평균 점유율의 분모. 모수 공연들의 전체 좌석 합이다. 점유율과 함께 생략된다.",
            example = "372",
            nullable = true)
        Long occupancyTotalSeats,
    @Schema(
            description =
                "일별 매출 추이. 예매 확정일 기준이며 **이 목록만** 조회 기간으로 잘린다. "
                    + "매출이 0인 날은 행이 없으므로 빈 날을 0으로 그릴지는 화면이 정한다. "
                    + "예매 서비스 조회에 실패하면 이 필드는 응답에서 생략된다.",
            nullable = true)
        List<DailyRevenue> dailyRevenues,
    @Schema(
            description =
                "장르별 매출 분포. 전체 기간이며 매출이 없는 장르도 0으로 포함해 항상 전체 장르가 내려간다. "
                    + "예매 서비스 조회에 실패하면 이 필드는 응답에서 생략된다.",
            nullable = true)
        List<GenreRevenue> genreRevenues) {

  @Schema(description = "일별 매출 한 행")
  public record DailyRevenue(
      @Schema(description = "예매 확정일", example = "2026-08-07") LocalDate date,
      @Schema(description = "그날 확정된 예매의 결제 금액 합", example = "3200000") long revenue) {}

  @Schema(description = "장르별 매출 한 행")
  public record GenreRevenue(
      @Schema(description = "장르 코드", example = "MUSICAL") Genre genre,
      @Schema(description = "장르 한글명", example = "뮤지컬") String genreName,
      @Schema(description = "해당 장르 공연들의 매출 합", example = "52000000") long revenue) {}
}
