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
 * <p>세 집계가 한 트랜잭션 안에서 실행되므로 같은 스냅샷을 본다. 요약의 매출과 공연별 매출의 합이 어긋나 보이는 일이 없다(단 {@code paidAmount}가 비어
 * 있는 확정 예매는 양쪽 모두에서 0으로 더해진다 — 그 건수는 요약의 {@code missingAmountBookings}에 있다).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingGetInternalStatsUseCase {

  private final BookingRepository bookingRepository;
  private final BookingGetAdminStatsUseCase bookingGetAdminStatsUseCase;

  /**
   * 요약·공연별·일별 집계를 한 번에 반환한다.
   *
   * @param from 일별 매출의 시작일(포함)
   * @param to 일별 매출의 종료일(포함). 내부에서 다음 날 0시 미만의 반열린 구간으로 바꿔 센다 — {@code confirmed_at}이 datetime이라
   *     "종료일 끝"을 값으로 표현하려 하면 마이크로초 절삭에 기대게 되기 때문이다.
   */
  public BookingInternalStatsResponse execute(LocalDate from, LocalDate to) {
    List<BookingPerformanceStatsRow> byPerformance =
        bookingRepository.aggregateStatsByPerformance(BookingStatus.CONFIRMED);

    List<BookingDailyRevenueRow> byDate =
        bookingRepository.aggregateDailyRevenue(
            BookingStatus.CONFIRMED, from.atStartOfDay(), to.plusDays(1).atStartOfDay());

    return new BookingInternalStatsResponse(
        bookingGetAdminStatsUseCase.execute(), byPerformance, byDate);
  }
}
