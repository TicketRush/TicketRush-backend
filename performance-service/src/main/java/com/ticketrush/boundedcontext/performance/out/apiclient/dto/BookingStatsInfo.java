package com.ticketrush.boundedcontext.performance.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/**
 * booking-service 예매 집계 내부 API의 응답 매핑 (#563).
 *
 * <p>이 DTO들은 {@code RestClient.builder()} 정적 팩토리로 만든 클라이언트가 쓰므로 앱의 {@code
 * JacksonConfig}(SNAKE_CASE)를 타지 않는다. 그래서 컴포넌트명과 다른 키에는 {@code @JsonProperty}가 반드시 필요하다 — 빠뜨리면 예외
 * 없이 <b>조용히 null/0</b>이 되어, 매출이 0원인 대시보드가 정상 응답으로 보인다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingStatsInfo(
    Summary summary,
    @JsonProperty("by_performance") List<PerformanceStat> byPerformance,
    @JsonProperty("by_date") List<DailyRevenue> byDate) {

  /** 전체 기간 요약. 관리자 예매 통계 API가 내리는 것과 같은 값이다. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Summary(
      @JsonProperty("completed_bookings") long completedBookings,
      @JsonProperty("total_revenue") long totalRevenue,
      @JsonProperty("revenue_complete") boolean revenueComplete,
      @JsonProperty("missing_amount_bookings") long missingAmountBookings) {}

  /** 공연별 예매·매출. 전체 기간이며 요청 기간의 영향을 받지 않는다. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PerformanceStat(
      @JsonProperty("performance_id") Long performanceId,
      @JsonProperty("confirmed_count") long confirmedCount,
      @JsonProperty("confirmed_revenue") long confirmedRevenue) {}

  /** 일별 매출. 매출이 0인 날은 행 자체가 없다. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DailyRevenue(LocalDate date, long revenue) {}
}
