package com.ticketrush.boundedcontext.booking.app.dto.response;

import java.util.List;

/**
 * 예매 집계 내부 조회 응답 (#563 관리자 대시보드, #590 관리자 공연 목록).
 *
 * <p>요약·공연별·일별을 <b>한 응답으로 묶는다.</b> 관리자 대시보드가 이 셋을 한 화면에 함께 그리므로, 나누면 호출자가 같은 화면을 위해 세 번 왕복하고 실패 조합도
 * 그만큼 늘어난다. 한 트랜잭션에서 뽑으므로 함께 계산한 값들이 같은 스냅샷을 본다는 성질도 따라온다.
 *
 * <p><b>계산하지 않은 축은 {@code null}이고 응답에서 키째 빠진다(#590).</b> 공통 Jackson 설정이 {@code NON_NULL}을 전역 기본값으로
 * 걸어 두어서다. 클라이언트가 보는 것은 {@code "summary": null}이 아니라 <b>키의 부재</b>이며, 그것은 "장애로 못 읽었다"가 아니라 <b>"요청하지
 * 않아 세지 않았다"</b>는 뜻이다.
 *
 * <p><b>필드 단위로 생략하지는 않는다.</b> 축 전체를 비우거나 전부 채우거나 둘 중 하나다. 내부 record들이 {@code long}·{@code boolean}
 * 원시 타입이라, 객체 안의 개별 키만 빠지면 소비측에서 예외 없이 0/false가 되어 매출 0원이 조용히 만들어진다.
 *
 * @param summary 전체 기간 요약. 관리자 예매 통계 API가 내리는 것과 <b>같은 유스케이스가 만든 같은 값</b>이다 — 매출 정의가 두 벌이 되지 않도록
 *     재사용한다. 공연 ID로 좁힌 요청에서는 계산하지 않는다(좁히면 두 화면의 총 매출이 갈린다).
 * @param byPerformance 공연별 예매·매출. 전체 기간이며 기간 파라미터의 영향을 받지 않는다. 공연 ID를 주면 그 공연들로 좁혀진다.
 * @param byDate 일별 매출. <b>이 목록만</b> 요청 기간으로 잘린다. 기간을 주지 않았거나 공연 ID로 좁힌 요청에서는 계산하지 않는다.
 */
public record BookingInternalStatsResponse(
    BookingAdminStatsResponse summary,
    List<BookingPerformanceStatsRow> byPerformance,
    List<BookingDailyRevenueRow> byDate) {}
