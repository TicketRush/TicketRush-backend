package com.ticketrush.boundedcontext.booking.app.dto.response;

import java.util.List;

/**
 * 예매 집계 내부 조회 응답 (#563 관리자 대시보드).
 *
 * <p>요약·공연별·일별을 <b>한 응답으로 묶는다.</b> 관리자 대시보드가 이 셋을 한 화면에 함께 그리므로, 나누면 호출자가 같은 화면을 위해 세 번 왕복하고 실패 조합도
 * 그만큼 늘어난다. 한 트랜잭션에서 뽑으므로 세 값이 같은 스냅샷을 본다는 성질도 따라온다.
 *
 * @param summary 전체 기간 요약. 관리자 예매 통계 API가 내리는 것과 <b>같은 유스케이스가 만든 같은 값</b>이다 — 매출 정의가 두 벌이 되지 않도록
 *     재사용한다.
 * @param byPerformance 공연별 예매·매출. 전체 기간이며 기간 파라미터의 영향을 받지 않는다.
 * @param byDate 일별 매출. <b>이 목록만</b> 요청 기간으로 잘린다.
 */
public record BookingInternalStatsResponse(
    BookingAdminStatsResponse summary,
    List<BookingPerformanceStatsRow> byPerformance,
    List<BookingDailyRevenueRow> byDate) {}
