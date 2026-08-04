package com.ticketrush.boundedcontext.booking.app.dto.response;

/**
 * 관리자 요약 통계의 DB 집계 결과 (#561). 내부 산출물이며 API로 노출되지 않는다.
 *
 * @param amountMissingCount 확정을 거쳤는데 {@code paidAmount}가 비어 있는 예매 수. {@code paid_amount} 도입 이전에 확정된
 *     행이 백필 전까지 여기 잡힌다. {@code SUM}은 NULL을 조용히 건너뛰므로, 이 값이 0이 아니면 매출은 실제보다 작다.
 */
public record BookingStatsCounts(
    long totalCount,
    long confirmedCount,
    long canceledCount,
    long confirmedRevenue,
    long amountMissingCount) {}
