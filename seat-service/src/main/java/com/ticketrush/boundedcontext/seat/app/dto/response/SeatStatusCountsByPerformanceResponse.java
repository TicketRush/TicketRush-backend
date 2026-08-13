package com.ticketrush.boundedcontext.seat.app.dto.response;

/**
 * 공연 단위 좌석 수 집계 한 행 (#563).
 *
 * <p>{@link SeatStatusCountsResponse}에 {@code performanceId}를 더한 형태다. 단건 조회는 경로 변수로 공연이 정해지지만 일괄
 * 조회는 응답에서 공연을 식별해야 하므로 필드가 하나 늘었다. 세는 규칙은 단건과 같다 — 만료된 HOLD는 {@code availableCount}에 들어가고, 그래서
 * {@code totalCount = availableCount + soldCount + holdCount}가 성립한다.
 */
public record SeatStatusCountsByPerformanceResponse(
    Long performanceId, Long totalCount, Long availableCount, Long soldCount, Long holdCount) {}
