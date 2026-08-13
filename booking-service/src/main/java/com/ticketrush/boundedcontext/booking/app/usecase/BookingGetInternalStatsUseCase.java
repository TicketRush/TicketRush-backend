package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingDailyRevenueRow;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingInternalStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPerformanceStatsRow;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예매 집계 내부 조회 (#563 관리자 대시보드).
 *
 * <p>요약은 {@link BookingGetAdminStatsUseCase}를 <b>그대로 호출한다.</b> 같은 값을 다시 정의하면 관리자 예매 화면과 대시보드가 다른
 * 매출을 내는 순간이 생긴다 — 어느 쪽이 맞는지 판정할 수단이 없는 종류의 불일치다. 완료·취소로 셀 상태를 그 유스케이스가 소유한다는 규율도 함께 지켜진다.
 *
 * <p>집계가 한 트랜잭션 안에서 실행되므로 같은 스냅샷을 본다. 요약의 매출과 공연별 매출의 합이 어긋나 보이는 일이 없다(단 {@code paidAmount}가 비어 있는
 * 확정 예매는 양쪽 모두에서 0으로 더해진다 — 그 건수는 요약의 {@code missingAmountBookings}에 있다).
 *
 * <p><b>세 축이 항상 함께 나오지는 않는다(#590).</b> 요청한 축만 계산하고 나머지는 {@code null}로 남긴다 — 자세한 규칙은 {@link
 * #execute(LocalDate, LocalDate, List)} 참고.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingGetInternalStatsUseCase {

  private final BookingRepository bookingRepository;
  private final BookingGetAdminStatsUseCase bookingGetAdminStatsUseCase;

  /**
   * 요청한 축만 계산해 반환한다. 계산하지 않은 축은 {@code null}이라 응답에서 키째 빠진다(전역 {@code NON_NULL}).
   *
   * <p><b>{@code performanceIds}를 주면 공연별 집계만 낸다(#590).</b> 요약과 일별 매출은 쿼리를 <b>아예 돌리지 않는다</b> — 관리자
   * 공연 목록은 페이지마다 이 API를 부르는데, 그때마다 WHERE 없는 전건 스캔 두 개를 더 돌리는 것이 이 파라미터로 없애려는 비용 그 자체다. 안 쓸 값을 위해
   * 오픈런 쓰기 핫패스인 {@code booking} 테이블을 두 번 더 훑지 않는다.
   *
   * <p>요약을 공연 ID로 좁히지 않는 이유는 그 정의를 {@link BookingGetAdminStatsUseCase}가 소유하기 때문이다. 좁히면 관리자 예매 통계
   * 화면과 대시보드의 총 매출이 갈린다.
   *
   * @param from 일별 매출의 시작일(포함). {@code to}와 함께 없으면 일별 매출을 계산하지 않는다.
   * @param to 일별 매출의 종료일(포함). 내부에서 다음 날 0시 미만의 반열린 구간으로 바꿔 센다 — {@code confirmed_at}이 datetime이라
   *     "종료일 끝"을 값으로 표현하려 하면 마이크로초 절삭에 기대게 되기 때문이다.
   * @param performanceIds 공연별 집계를 좁힐 공연 ID. null이거나 비어 있으면 전 공연을 집계하고 요약도 함께 낸다.
   */
  public BookingInternalStatsResponse execute(
      LocalDate from, LocalDate to, List<Long> performanceIds) {
    boolean filtered = performanceIds != null && !performanceIds.isEmpty();

    if (filtered) {
      return new BookingInternalStatsResponse(
          null,
          bookingRepository.aggregateStatsByPerformanceIdIn(
              BookingStatus.CONFIRMED, performanceIds),
          null);
    }

    List<BookingPerformanceStatsRow> byPerformance =
        bookingRepository.aggregateStatsByPerformance(BookingStatus.CONFIRMED);

    List<BookingDailyRevenueRow> byDate =
        (from == null || to == null)
            ? null
            : bookingRepository.aggregateDailyRevenue(
                BookingStatus.CONFIRMED, from.atStartOfDay(), to.plusDays(1).atStartOfDay());

    return new BookingInternalStatsResponse(
        bookingGetAdminStatsUseCase.execute(), byPerformance, byDate);
  }
}
