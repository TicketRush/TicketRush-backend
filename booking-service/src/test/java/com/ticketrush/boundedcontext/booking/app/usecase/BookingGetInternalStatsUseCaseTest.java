package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingAdminStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingDailyRevenueRow;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingInternalStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPerformanceStatsRow;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingGetInternalStatsUseCaseTest {

  @InjectMocks private BookingGetInternalStatsUseCase useCase;

  @Mock private BookingRepository bookingRepository;

  @Mock private BookingGetAdminStatsUseCase bookingGetAdminStatsUseCase;

  @Test
  @DisplayName("성공: 공연 ID 없이 기간을 주면 요약·공연별·일별을 모두 계산한다")
  void execute_withoutPerformanceIds_returnsAllThreeAxes() {
    // given
    LocalDate from = LocalDate.of(2026, 7, 9);
    LocalDate to = LocalDate.of(2026, 8, 7);
    given(bookingGetAdminStatsUseCase.execute())
        .willReturn(new BookingAdminStatsResponse(1250L, 980L, 120L, 147_000_000L, false, 3L));
    given(bookingRepository.aggregateStatsByPerformance(BookingStatus.CONFIRMED))
        .willReturn(List.of(new BookingPerformanceStatsRow(100L, 30L, 5_000_000L)));
    given(
            bookingRepository.aggregateDailyRevenue(
                any(BookingStatus.class), any(LocalDateTime.class), any(LocalDateTime.class)))
        .willReturn(List.of(new BookingDailyRevenueRow(LocalDate.of(2026, 8, 1), 1_000_000L)));

    // when
    BookingInternalStatsResponse response = useCase.execute(from, to, null);

    // then
    assertThat(response.summary()).isNotNull();
    assertThat(response.byPerformance()).hasSize(1);
    assertThat(response.byDate()).hasSize(1);
  }

  /**
   * 이 테스트가 고정하는 것은 응답 모양이 아니라 <b>쿼리를 돌리지 않는다</b>는 사실이다. 관리자 공연 목록은 페이지마다 이 유스케이스를 부르므로, 안 쓸 요약·일별을
   * 계산하면 WHERE 없는 전건 스캔 두 개가 페이지마다 반복된다 — #590이 없애려는 비용 그 자체다. 응답만 비우고 쿼리는 도는 구현으로 되돌아가도 이 단언이 없으면
   * 아무 테스트도 깨지지 않는다.
   */
  @Test
  @DisplayName("성공: 공연 ID를 주면 공연별만 계산하고 요약·일별 쿼리는 아예 돌리지 않는다 (#590)")
  void execute_withPerformanceIds_skipsSummaryAndDailyQueries() {
    // given
    List<Long> performanceIds = List.of(100L, 200L);
    given(
            bookingRepository.aggregateStatsByPerformanceIdIn(
                BookingStatus.CONFIRMED, performanceIds))
        .willReturn(List.of(new BookingPerformanceStatsRow(100L, 30L, 5_000_000L)));

    // when
    BookingInternalStatsResponse response = useCase.execute(null, null, performanceIds);

    // then
    assertThat(response.byPerformance()).hasSize(1);
    assertThat(response.summary()).isNull();
    assertThat(response.byDate()).isNull();

    verifyNoInteractions(bookingGetAdminStatsUseCase);
    verify(bookingRepository, never()).aggregateStatsByPerformance(any());
    verify(bookingRepository, never()).aggregateDailyRevenue(any(), any(), any());
  }

  @Test
  @DisplayName("성공: 공연 ID를 주면 기간을 함께 줘도 일별 매출을 계산하지 않는다")
  void execute_withPerformanceIdsAndDates_stillSkipsDaily() {
    // given
    List<Long> performanceIds = List.of(100L);
    given(
            bookingRepository.aggregateStatsByPerformanceIdIn(
                BookingStatus.CONFIRMED, performanceIds))
        .willReturn(List.of());

    // when
    BookingInternalStatsResponse response =
        useCase.execute(LocalDate.of(2026, 7, 9), LocalDate.of(2026, 8, 7), performanceIds);

    // then
    assertThat(response.byDate()).isNull();
    verify(bookingRepository, never()).aggregateDailyRevenue(any(), any(), any());
  }

  @Test
  @DisplayName("성공: 공연 ID가 빈 목록이면 전건 집계로 돌아간다")
  void execute_withEmptyPerformanceIds_fallsBackToAll() {
    // given: 빈 목록을 IN에 넘기면 JPQL이 성립하지 않는다
    given(bookingGetAdminStatsUseCase.execute())
        .willReturn(new BookingAdminStatsResponse(0L, 0L, 0L, 0L, true, 0L));
    given(bookingRepository.aggregateStatsByPerformance(BookingStatus.CONFIRMED))
        .willReturn(List.of());

    // when
    BookingInternalStatsResponse response = useCase.execute(null, null, List.of());

    // then
    assertThat(response.summary()).isNotNull();
    verify(bookingRepository, never()).aggregateStatsByPerformanceIdIn(any(), any());
  }

  @Test
  @DisplayName("성공: 기간이 없으면 일별 매출만 비우고 요약·공연별은 계산한다")
  void execute_withoutDates_skipsOnlyDaily() {
    // given
    given(bookingGetAdminStatsUseCase.execute())
        .willReturn(new BookingAdminStatsResponse(10L, 5L, 1L, 500L, true, 0L));
    given(bookingRepository.aggregateStatsByPerformance(BookingStatus.CONFIRMED))
        .willReturn(List.of(new BookingPerformanceStatsRow(100L, 5L, 500L)));

    // when
    BookingInternalStatsResponse response = useCase.execute(null, null, null);

    // then
    assertThat(response.summary()).isNotNull();
    assertThat(response.byPerformance()).hasSize(1);
    assertThat(response.byDate()).isNull();
    verify(bookingRepository, never()).aggregateDailyRevenue(any(), any(), any());
  }
}
