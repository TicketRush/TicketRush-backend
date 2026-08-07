package com.ticketrush.boundedcontext.booking.app.dto.response;

/**
 * 공연 단위 예매 집계 한 행 (#563 관리자 대시보드).
 *
 * @param confirmedCount 확정된 예매 수. 1인 1매라 예매 건수가 곧 좌석 수지만, <b>좌석 서비스의 판매 좌석 수와 항상 같지는 않다</b> — 결제는
 *     확정됐는데 좌석 확정에 실패해 보상 환불이 진행 중인 창(#492)에서 갈린다. 화면에 "판매된 좌석 수"로 보여줄 값은 좌석 쪽 집계이며, 이 값은 예매 관점의
 *     수치이자 두 집계를 대조할 때 쓴다.
 * @param confirmedRevenue 확정된 예매의 실제 결제 금액 합. {@code paidAmount}가 비어 있는 행은 0으로 더해지므로 요약의 {@code
 *     missingAmountBookings}가 0이 아니면 이 값도 실제보다 작다.
 */
public record BookingPerformanceStatsRow(
    Long performanceId, long confirmedCount, long confirmedRevenue) {}
