package com.ticketrush.boundedcontext.booking.app.dto.response;

import java.time.LocalDate;

/**
 * 일별 매출 한 행 (#563 관리자 대시보드).
 *
 * <p>날짜 축은 예매 확정 시각({@code confirmedAt})이다. 공연 날짜가 아니라 <b>돈이 들어온 날</b>을 세며, 그래야 매출 추이가 판매 활동을 반영한다.
 *
 * <p><b>매출이 0인 날은 행이 없다.</b> GROUP BY는 행이 있는 그룹만 만들기 때문이다. 차트에 빈 날을 0으로 그릴지는 화면의 몫이다.
 */
public record BookingDailyRevenueRow(LocalDate date, long revenue) {}
