package com.ticketrush.boundedcontext.booking.app.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceInfoResponse;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 관리자 통계 합산 로직 (#561). 돈을 다루는 계산이라 순수 함수로 떼어 고정한다.
 *
 * <p>특히 <b>부분 합을 절대 노출하지 않는다</b>는 규칙이 핵심이다 — 공연 조회가 끊겨 축소된 매출은 정상값과 구분되지 않아 "매출 감소"로 오독된다.
 */
class BookingAdminStatsResponseTest {

  private static PerformanceInfoResponse performance(Long price) {
    return new PerformanceInfoResponse(
        "오페라의 유령", LocalDate.of(2026, 5, 22), LocalTime.of(19, 30), "서울 예술의전당", price);
  }

  @Test
  @DisplayName("공연 가격이 모두 조회되면 완료 예매 수 × 공연 가격을 합산한다")
  void sums_revenue_from_confirmed_count_and_price() {
    // given — 공연 1: 완료 3건 × 10000, 공연 2: 완료 2건 × 25000
    List<BookingPerformanceStatsRow> rows =
        List.of(
            new BookingPerformanceStatsRow(1L, 5, 3, 2),
            new BookingPerformanceStatsRow(2L, 4, 2, 1));
    Map<Long, PerformanceInfoResponse> performances =
        Map.of(1L, performance(10_000L), 2L, performance(25_000L));

    // when
    BookingAdminStatsResponse response = BookingAdminStatsResponse.of(rows, performances);

    // then
    assertThat(response.totalBookings()).isEqualTo(9);
    assertThat(response.completedBookings()).isEqualTo(5);
    assertThat(response.canceledBookings()).isEqualTo(3);
    assertThat(response.totalRevenue()).isEqualTo(80_000L);
  }

  @Test
  @DisplayName("매출에 기여하는 공연이 하나라도 조회되지 않으면 부분 합이 아니라 null을 내린다")
  void returns_null_revenue_when_a_contributing_performance_is_missing() {
    // given — 공연 2의 가격을 조회하지 못했다(장애·예산 절단·삭제된 공연)
    List<BookingPerformanceStatsRow> rows =
        List.of(
            new BookingPerformanceStatsRow(1L, 5, 3, 2),
            new BookingPerformanceStatsRow(2L, 4, 2, 1));
    Map<Long, PerformanceInfoResponse> performances = Map.of(1L, performance(10_000L));

    // when
    BookingAdminStatsResponse response = BookingAdminStatsResponse.of(rows, performances);

    // then — 30000(공연 1분)이 아니라 null이어야 한다
    assertThat(response.totalRevenue()).isNull();
    assertThat(response.totalBookings()).isEqualTo(9);
    assertThat(response.completedBookings()).isEqualTo(5);
    assertThat(response.canceledBookings()).isEqualTo(3);
  }

  @Test
  @DisplayName("공연은 조회됐지만 가격이 null이면 매출을 null로 내린다")
  void returns_null_revenue_when_price_is_null() {
    // given
    List<BookingPerformanceStatsRow> rows = List.of(new BookingPerformanceStatsRow(1L, 5, 3, 2));
    Map<Long, PerformanceInfoResponse> performances = Map.of(1L, performance(null));

    // when
    BookingAdminStatsResponse response = BookingAdminStatsResponse.of(rows, performances);

    // then
    assertThat(response.totalRevenue()).isNull();
  }

  @Test
  @DisplayName("완료 예매가 없는 공연은 가격을 몰라도 매출 집계를 무너뜨리지 않는다")
  void ignores_performances_without_confirmed_bookings() {
    // given — 공연 2는 취소만 있어 매출에 기여하지 않으므로 가격이 없어도 된다
    List<BookingPerformanceStatsRow> rows =
        List.of(
            new BookingPerformanceStatsRow(1L, 5, 3, 2),
            new BookingPerformanceStatsRow(2L, 4, 0, 4));
    Map<Long, PerformanceInfoResponse> performances = Map.of(1L, performance(10_000L));

    // when
    BookingAdminStatsResponse response = BookingAdminStatsResponse.of(rows, performances);

    // then
    assertThat(response.totalRevenue()).isEqualTo(30_000L);
    assertThat(response.canceledBookings()).isEqualTo(6);
  }

  @Test
  @DisplayName("완료된 예매가 하나도 없으면 매출은 null이 아니라 0이다")
  void returns_zero_revenue_when_no_confirmed_bookings() {
    // given
    List<BookingPerformanceStatsRow> rows = List.of(new BookingPerformanceStatsRow(1L, 4, 0, 4));

    // when — 조회할 공연이 없으므로 빈 맵이다
    BookingAdminStatsResponse response = BookingAdminStatsResponse.of(rows, Map.of());

    // then — 0은 "매출 없음", null은 "집계 불가"로 뜻이 다르다
    assertThat(response.totalRevenue()).isZero();
  }

  @Test
  @DisplayName("예매가 하나도 없으면 모든 지표가 0이다")
  void returns_zeros_when_no_bookings() {
    // when
    BookingAdminStatsResponse response = BookingAdminStatsResponse.of(List.of(), Map.of());

    // then
    assertThat(response.totalBookings()).isZero();
    assertThat(response.completedBookings()).isZero();
    assertThat(response.canceledBookings()).isZero();
    assertThat(response.totalRevenue()).isZero();
  }
}
