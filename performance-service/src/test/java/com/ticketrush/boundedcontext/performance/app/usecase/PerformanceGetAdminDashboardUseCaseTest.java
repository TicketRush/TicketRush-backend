package com.ticketrush.boundedcontext.performance.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceAdminDashboardResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceAggregateRow;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.apiclient.BookingRestClient;
import com.ticketrush.boundedcontext.performance.out.apiclient.SeatRestClient;
import com.ticketrush.boundedcontext.performance.out.apiclient.dto.BookingStatsInfo;
import com.ticketrush.boundedcontext.performance.out.apiclient.dto.SeatCountsInfo;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PerformanceGetAdminDashboardUseCaseTest {

  @Mock private PerformanceRepository performanceRepository;
  @Mock private BookingRestClient bookingRestClient;
  @Mock private SeatRestClient seatRestClient;

  @InjectMocks private PerformanceGetAdminDashboardUseCase useCase;

  private static final LocalDate FROM = LocalDate.of(2026, 7, 9);
  private static final LocalDate TO = LocalDate.of(2026, 8, 7);

  @Test
  @DisplayName("요약·점유율·장르별 매출을 한 응답으로 조립한다")
  void execute_AssemblesAllSections() {
    // given: 판매중 뮤지컬(100), 판매종료 콘서트(200), 판매 전 뮤지컬(300)
    given(performanceRepository.findAllAggregateRows())
        .willReturn(
            List.of(
                row(100L, Genre.MUSICAL, PerformanceStatus.ON_SALE),
                row(200L, Genre.CONCERT, PerformanceStatus.CLOSED),
                row(300L, Genre.MUSICAL, PerformanceStatus.UPCOMING)));
    given(bookingRestClient.getStats(FROM, TO))
        .willReturn(
            Optional.of(
                new BookingStatsInfo(
                    new BookingStatsInfo.Summary(980L, 147_000_000L, true, 0L),
                    List.of(
                        new BookingStatsInfo.PerformanceStat(100L, 30L, 5_000_000L),
                        new BookingStatsInfo.PerformanceStat(200L, 10L, 2_000_000L)),
                    List.of(
                        new BookingStatsInfo.DailyRevenue(LocalDate.of(2026, 8, 1), 1_000_000L)))));
    given(seatRestClient.getSeatCounts())
        .willReturn(
            Map.of(
                100L, seat(100L, 120L, 30L),
                200L, seat(200L, 80L, 10L),
                300L, seat(300L, 500L, 0L)));

    // when
    PerformanceAdminDashboardResponse response = useCase.execute(FROM, TO);

    // then
    assertThat(response.registeredPerformances()).isEqualTo(3);
    assertThat(response.soldTickets()).isEqualTo(980L);
    assertThat(response.totalRevenue()).isEqualTo(147_000_000L);
    assertThat(response.revenueComplete()).isTrue();

    // 판매 전 공연(300)은 점유율 모수에서 빠진다 — 넣으면 500석이 분모에 깔려 지표가 왜곡된다
    assertThat(response.occupancySoldSeats()).isEqualTo(40L);
    assertThat(response.occupancyTotalSeats()).isEqualTo(200L);
    assertThat(response.averageOccupancyRate()).isEqualTo(0.2);

    assertThat(response.dailyRevenues()).hasSize(1);
    assertThat(response.genreRevenues()).hasSize(Genre.values().length);
    assertThat(response.genreRevenues())
        .anySatisfy(
            g -> {
              assertThat(g.genre()).isEqualTo(Genre.MUSICAL);
              assertThat(g.revenue()).isEqualTo(5_000_000L);
            })
        .anySatisfy(
            g -> {
              assertThat(g.genre()).isEqualTo(Genre.BALLET);
              assertThat(g.revenue()).isZero();
            });
  }

  @Test
  @DisplayName("가중 평균이라 큰 공연이 작은 공연에 희석되지 않는다")
  void execute_OccupancyIsWeightedNotArithmeticMean() {
    // given: 1000석 중 100석(10%)과 20석 중 18석(90%)
    given(performanceRepository.findAllAggregateRows())
        .willReturn(
            List.of(
                row(1L, Genre.CONCERT, PerformanceStatus.ON_SALE),
                row(2L, Genre.CONCERT, PerformanceStatus.ON_SALE)));
    given(bookingRestClient.getStats(FROM, TO)).willReturn(Optional.empty());
    given(seatRestClient.getSeatCounts())
        .willReturn(Map.of(1L, seat(1L, 1000L, 100L), 2L, seat(2L, 20L, 18L)));

    // when
    PerformanceAdminDashboardResponse response = useCase.execute(FROM, TO);

    // then: 산술평균이면 50%지만 가중평균은 118/1020 = 11.57%
    assertThat(response.averageOccupancyRate()).isEqualTo(0.1157);
  }

  @Test
  @DisplayName("예매 서비스 조회 실패 시 매출 관련 필드만 null이고 공연 수는 그대로다")
  void execute_WhenBookingFails_OnlyRevenueFieldsAreNull() {
    // given
    given(performanceRepository.findAllAggregateRows())
        .willReturn(List.of(row(1L, Genre.JAZZ, PerformanceStatus.ON_SALE)));
    given(bookingRestClient.getStats(FROM, TO)).willReturn(Optional.empty());
    given(seatRestClient.getSeatCounts()).willReturn(Map.of(1L, seat(1L, 100L, 25L)));

    // when
    PerformanceAdminDashboardResponse response = useCase.execute(FROM, TO);

    // then
    assertThat(response.registeredPerformances()).isEqualTo(1);
    assertThat(response.soldTickets()).isNull();
    assertThat(response.totalRevenue()).isNull();
    assertThat(response.revenueComplete()).isNull();
    assertThat(response.dailyRevenues()).isNull();
    assertThat(response.genreRevenues()).isNull();
    // 좌석 축은 살아 있다
    assertThat(response.averageOccupancyRate()).isEqualTo(0.25);
  }

  @Test
  @DisplayName("예매 응답에 요약이 비어 있어도 500이 아니라 매출 축만 비운다")
  void execute_WhenSummaryMissing_TreatsRevenueAxisAsUnknown() {
    // given: 200 + 정상 JSON인데 summary만 null인 경우(예: 예매 서비스가 필드명을 바꿈).
    // RestClientException도 파싱 실패도 아니라 여기까지 그대로 도달한다.
    given(performanceRepository.findAllAggregateRows())
        .willReturn(List.of(row(1L, Genre.JAZZ, PerformanceStatus.ON_SALE)));
    given(bookingRestClient.getStats(FROM, TO))
        .willReturn(Optional.of(new BookingStatsInfo(null, List.of(), List.of())));
    given(seatRestClient.getSeatCounts()).willReturn(Map.of(1L, seat(1L, 100L, 25L)));

    // when
    PerformanceAdminDashboardResponse response = useCase.execute(FROM, TO);

    // then: 역참조하면 NPE로 500이 된다 — fail-open이어야 할 경로다
    assertThat(response.soldTickets()).isNull();
    assertThat(response.totalRevenue()).isNull();
    assertThat(response.revenueComplete()).isNull();
    assertThat(response.missingAmountBookings()).isNull();
    assertThat(response.registeredPerformances()).isEqualTo(1);
    assertThat(response.averageOccupancyRate()).isEqualTo(0.25);
    // 일별·장르별은 각자의 목록이 살아 있으면 그대로 채운다(축별 독립 저하)
    assertThat(response.dailyRevenues()).isEmpty();
    assertThat(response.genreRevenues()).hasSize(Genre.values().length);
  }

  @Test
  @DisplayName("일별 매출을 읽지 못하면 빈 목록이 아니라 null이다")
  void execute_WhenByDateMissing_ReturnsNullNotEmptyList() {
    // given: 빈 목록은 "그 기간에 매출이 없었다"는 사실이라 "못 읽었다"와 구분되어야 한다
    given(performanceRepository.findAllAggregateRows()).willReturn(List.of());
    given(bookingRestClient.getStats(FROM, TO))
        .willReturn(
            Optional.of(
                new BookingStatsInfo(
                    new BookingStatsInfo.Summary(0L, 0L, true, 0L), List.of(), null)));
    given(seatRestClient.getSeatCounts()).willReturn(Map.of());

    // when
    PerformanceAdminDashboardResponse response = useCase.execute(FROM, TO);

    // then
    assertThat(response.dailyRevenues()).isNull();
    // 공연별은 읽었으므로 장르별은 전 장르 0원으로 채운다 — 차트가 전체 축을 그려야 하기 때문이다
    assertThat(response.genreRevenues()).hasSize(Genre.values().length);
  }

  @Test
  @DisplayName("공연별 집계를 읽지 못하면 장르별 매출도 null이다 — 전 장르 0원으로 그리면 '안 팔림'으로 읽힌다")
  void execute_WhenByPerformanceMissing_GenreRevenuesAreNull() {
    // given
    given(performanceRepository.findAllAggregateRows())
        .willReturn(List.of(row(1L, Genre.MUSICAL, PerformanceStatus.ON_SALE)));
    given(bookingRestClient.getStats(FROM, TO))
        .willReturn(
            Optional.of(
                new BookingStatsInfo(
                    new BookingStatsInfo.Summary(0L, 0L, true, 0L), null, List.of())));
    given(seatRestClient.getSeatCounts()).willReturn(Map.of());

    // when
    PerformanceAdminDashboardResponse response = useCase.execute(FROM, TO);

    // then
    assertThat(response.genreRevenues()).isNull();
    // 일별은 읽었으므로 살아 있다 — 축별로 독립해 저하된다
    assertThat(response.dailyRevenues()).isEmpty();
  }

  @Test
  @DisplayName("좌석 서비스 조회 실패 시 점유율만 null이고 매출은 그대로다")
  void execute_WhenSeatFails_OnlyOccupancyIsNull() {
    // given
    given(performanceRepository.findAllAggregateRows())
        .willReturn(List.of(row(1L, Genre.JAZZ, PerformanceStatus.ON_SALE)));
    given(bookingRestClient.getStats(FROM, TO))
        .willReturn(
            Optional.of(
                new BookingStatsInfo(
                    new BookingStatsInfo.Summary(10L, 500_000L, true, 0L), List.of(), List.of())));
    given(seatRestClient.getSeatCounts()).willReturn(Map.of());

    // when
    PerformanceAdminDashboardResponse response = useCase.execute(FROM, TO);

    // then
    assertThat(response.averageOccupancyRate()).isNull();
    assertThat(response.occupancySoldSeats()).isNull();
    assertThat(response.occupancyTotalSeats()).isNull();
    assertThat(response.totalRevenue()).isEqualTo(500_000L);
  }

  @Test
  @DisplayName("좌석이 아직 생성되지 않은 공연은 점유율 분모에서 빠진다")
  void execute_PerformanceWithoutSeats_IsExcludedFromDenominator() {
    // given: 좌석 정보가 있는 공연 1개, 방금 등록돼 좌석이 없는 공연 1개
    given(performanceRepository.findAllAggregateRows())
        .willReturn(
            List.of(
                row(1L, Genre.CONCERT, PerformanceStatus.ON_SALE),
                row(2L, Genre.CONCERT, PerformanceStatus.ON_SALE)));
    given(bookingRestClient.getStats(FROM, TO)).willReturn(Optional.empty());
    given(seatRestClient.getSeatCounts()).willReturn(Map.of(1L, seat(1L, 100L, 50L)));

    // when
    PerformanceAdminDashboardResponse response = useCase.execute(FROM, TO);

    // then: 0석으로 채웠다면 분모가 100 그대로여도 '매진 불가능한 공연'이 섞여 평균이 흐려진다
    assertThat(response.occupancyTotalSeats()).isEqualTo(100L);
    assertThat(response.averageOccupancyRate()).isEqualTo(0.5);
  }

  @Test
  @DisplayName("공연이 하나도 없어도 0으로 나누지 않고 200을 반환한다")
  void execute_WhenNoPerformances_DoesNotDivideByZero() {
    // given
    given(performanceRepository.findAllAggregateRows()).willReturn(List.of());
    given(bookingRestClient.getStats(FROM, TO)).willReturn(Optional.empty());
    given(seatRestClient.getSeatCounts()).willReturn(Map.of());

    // when
    PerformanceAdminDashboardResponse response = useCase.execute(FROM, TO);

    // then
    assertThat(response.registeredPerformances()).isZero();
    assertThat(response.averageOccupancyRate()).isNull();
  }

  @Test
  @DisplayName("좌석이 0석인 공연만 있으면 점유율은 0%가 아니라 null이다")
  void execute_WhenTotalSeatsZero_ReturnsNullRate() {
    // given
    given(performanceRepository.findAllAggregateRows())
        .willReturn(List.of(row(1L, Genre.CONCERT, PerformanceStatus.ON_SALE)));
    given(bookingRestClient.getStats(FROM, TO)).willReturn(Optional.empty());
    given(seatRestClient.getSeatCounts()).willReturn(Map.of(1L, seat(1L, 0L, 0L)));

    // when
    PerformanceAdminDashboardResponse response = useCase.execute(FROM, TO);

    // then
    assertThat(response.averageOccupancyRate()).isNull();
  }

  @Test
  @DisplayName("기간 상한(92일)을 넘으면 400으로 거부한다")
  void execute_WhenPeriodExceedsLimit_Throws400() {
    assertThatThrownBy(() -> useCase.execute(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        .isEqualTo(ErrorStatus.PERFORMANCE_DASHBOARD_PERIOD_TOO_LONG);
  }

  @Test
  @DisplayName("정확히 92일이면 통과한다")
  void execute_WhenPeriodIsExactlyLimit_Succeeds() {
    // given: 1/1 ~ 4/2 = 92일(양끝 포함)
    LocalDate from = LocalDate.of(2026, 1, 1);
    LocalDate to = from.plusDays(91);
    given(performanceRepository.findAllAggregateRows()).willReturn(List.of());
    given(bookingRestClient.getStats(from, to)).willReturn(Optional.empty());
    given(seatRestClient.getSeatCounts()).willReturn(Map.of());

    // when & then
    assertThat(useCase.execute(from, to)).isNotNull();
  }

  @Test
  @DisplayName("시작일이 종료일보다 뒤면 400으로 거부한다")
  void execute_WhenPeriodReversed_Throws400() {
    assertThatThrownBy(() -> useCase.execute(TO, FROM))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorStatus())
        // 상한 초과와 다른 코드여야 프론트가 어느 쪽인지 알 수 있다
        .isEqualTo(ErrorStatus.PERFORMANCE_INVALID_DASHBOARD_PERIOD);
  }

  @Test
  @DisplayName("기간을 지정하지 않으면 오늘을 포함한 최근 30일을 쓴다")
  void execute_WhenPeriodOmitted_UsesLast30Days() {
    // given
    LocalDate today = LocalDate.now();
    given(performanceRepository.findAllAggregateRows()).willReturn(List.of());
    given(bookingRestClient.getStats(today.minusDays(29), today)).willReturn(Optional.empty());
    given(seatRestClient.getSeatCounts()).willReturn(Map.of());

    // when & then: 위 given과 다른 기간으로 호출되면 stub이 맞지 않아 실패한다
    assertThat(useCase.execute(null, null)).isNotNull();
  }

  private PerformanceAggregateRow row(Long id, Genre genre, PerformanceStatus status) {
    return new PerformanceAggregateRow(id, genre, status);
  }

  private SeatCountsInfo seat(Long performanceId, long total, long sold) {
    return new SeatCountsInfo(performanceId, total, sold);
  }
}
