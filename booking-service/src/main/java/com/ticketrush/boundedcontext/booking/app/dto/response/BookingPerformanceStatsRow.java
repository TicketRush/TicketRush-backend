package com.ticketrush.boundedcontext.booking.app.dto.response;

/**
 * 공연별 예매 집계 한 행 (#561). 관리자 통계의 중간 산출물이며 API로 노출되지 않는다.
 *
 * <p>매출이 공연 가격 기반이라 공연 단위 그룹이 어차피 필요하다. 그 김에 전체·완료·취소 카운트도 같은 스캔에서 뽑아 booking 테이블을 한 번만 훑는다.
 */
public record BookingPerformanceStatsRow(
    Long performanceId, long totalCount, long confirmedCount, long canceledCount) {}
