package com.ticketrush.boundedcontext.performance.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceAdminSummaryResponse;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.apiclient.BookingRestClient;
import com.ticketrush.boundedcontext.performance.out.apiclient.SeatRestClient;
import com.ticketrush.boundedcontext.performance.out.apiclient.dto.BookingStatsInfo;
import com.ticketrush.boundedcontext.performance.out.apiclient.dto.SeatCountsInfo;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PerformanceGetAdminListUseCaseTest {

  @Mock private PerformanceRepository performanceRepository;
  @Mock private BookingRestClient bookingRestClient;
  @Mock private SeatRestClient seatRestClient;

  @InjectMocks private PerformanceGetAdminListUseCase useCase;

  private static final OffsetPageRequest PAGE = new OffsetPageRequest(0, 10);

  @Test
  @DisplayName("판매·점유율·매진·매출을 함께 내린다")
  void execute_EnrichesWithAggregates() {
    // given: 120석 중 38석 판매
    givenPage(performance(1L, "뮤지컬 팬텀", Genre.MUSICAL, PerformanceStatus.ON_SALE));
    givenBookingStats(new BookingStatsInfo.PerformanceStat(1L, 38L, 5_320_000L));
    given(seatRestClient.getSeatCounts()).willReturn(Map.of(1L, new SeatCountsInfo(1L, 120L, 38L)));

    // when
    PerformanceAdminSummaryResponse row = useCase.execute(PAGE).getContent().get(0);

    // then
    assertThat(row.performanceId()).isEqualTo(1L);
    assertThat(row.genreName()).isEqualTo("뮤지컬");
    assertThat(row.soldSeats()).isEqualTo(38L);
    assertThat(row.totalSeats()).isEqualTo(120L);
    assertThat(row.occupancyRate()).isEqualTo(0.3167);
    assertThat(row.soldOut()).isFalse();
    assertThat(row.revenue()).isEqualTo(5_320_000L);
  }

  @Test
  @DisplayName("판매 좌석이 전체 좌석에 도달하면 매진이다")
  void execute_WhenAllSeatsSold_MarksSoldOut() {
    // given
    givenPage(performance(1L, "매진 공연", Genre.CONCERT, PerformanceStatus.ON_SALE));
    givenBookingStats(new BookingStatsInfo.PerformanceStat(1L, 120L, 10_000_000L));
    given(seatRestClient.getSeatCounts())
        .willReturn(Map.of(1L, new SeatCountsInfo(1L, 120L, 120L)));

    // when
    PerformanceAdminSummaryResponse row = useCase.execute(PAGE).getContent().get(0);

    // then
    assertThat(row.soldOut()).isTrue();
    assertThat(row.occupancyRate()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("예매 서비스 조회에 성공했지만 예매가 없는 공연의 매출은 0이다")
  void execute_WhenNoBookings_RevenueIsZeroNotNull() {
    // given: 집계 응답에 이 공연이 없다 = 확정된 예매가 없다
    givenPage(performance(1L, "신규 공연", Genre.JAZZ, PerformanceStatus.ON_SALE));
    given(bookingRestClient.getStats(any(), any()))
        .willReturn(
            Optional.of(
                new BookingStatsInfo(
                    new BookingStatsInfo.Summary(0L, 0L, true, 0L), List.of(), List.of())));
    given(seatRestClient.getSeatCounts()).willReturn(Map.of(1L, new SeatCountsInfo(1L, 120L, 0L)));

    // when
    PerformanceAdminSummaryResponse row = useCase.execute(PAGE).getContent().get(0);

    // then: 0과 null을 갈라야 "안 팔렸다"와 "못 읽었다"가 구분된다
    assertThat(row.revenue()).isZero();
  }

  @Test
  @DisplayName("예매 서비스 조회에 실패하면 매출은 null이고 좌석 필드는 그대로다")
  void execute_WhenBookingFails_RevenueIsNull() {
    // given
    givenPage(performance(1L, "공연", Genre.JAZZ, PerformanceStatus.ON_SALE));
    given(bookingRestClient.getStats(any(), any())).willReturn(Optional.empty());
    given(seatRestClient.getSeatCounts()).willReturn(Map.of(1L, new SeatCountsInfo(1L, 120L, 10L)));

    // when
    PerformanceAdminSummaryResponse row = useCase.execute(PAGE).getContent().get(0);

    // then
    assertThat(row.revenue()).isNull();
    assertThat(row.soldSeats()).isEqualTo(10L);
  }

  @Test
  @DisplayName("좌석이 아직 생성되지 않은 공연은 좌석 관련 필드가 모두 null이다")
  void execute_WhenSeatsMissing_SeatFieldsAreNull() {
    // given: 좌석 생성은 공연 등록 이벤트를 받아 비동기로 일어난다
    givenPage(performance(1L, "방금 등록한 공연", Genre.BALLET, PerformanceStatus.UPCOMING));
    givenBookingStats();
    given(seatRestClient.getSeatCounts()).willReturn(Map.of());

    // when
    PerformanceAdminSummaryResponse row = useCase.execute(PAGE).getContent().get(0);

    // then
    assertThat(row.soldSeats()).isNull();
    assertThat(row.totalSeats()).isNull();
    assertThat(row.occupancyRate()).isNull();
    assertThat(row.soldOut()).isNull();
    // 공연 자체 정보는 항상 채워진다
    assertThat(row.title()).isEqualTo("방금 등록한 공연");
    assertThat(row.performanceStatus()).isEqualTo(PerformanceStatus.UPCOMING);
  }

  private void givenPage(Performance performance) {
    Page<Performance> page = new PageImpl<>(List.of(performance), PageRequest.of(0, 10), 1);
    given(performanceRepository.findAll(any(Pageable.class))).willReturn(page);
  }

  private void givenBookingStats(BookingStatsInfo.PerformanceStat... stats) {
    given(bookingRestClient.getStats(any(), any()))
        .willReturn(
            Optional.of(
                new BookingStatsInfo(
                    new BookingStatsInfo.Summary(0L, 0L, true, 0L), List.of(stats), List.of())));
  }

  private Performance performance(Long id, String title, Genre genre, PerformanceStatus status) {
    Performance performance = mock(Performance.class);
    given(performance.getId()).willReturn(id);
    given(performance.getTitle()).willReturn(title);
    given(performance.getGenre()).willReturn(genre);
    given(performance.getShowDate()).willReturn(LocalDate.of(2026, 9, 1));
    given(performance.getPerformanceStatus()).willReturn(status);
    return performance;
  }
}
