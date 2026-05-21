package com.ticketrush.boundedcontext.seat.app.dto.response;

public record SeatStatusCountsResponse(
    Long totalCount, Long availableCount, Long soldCount, Long holdCount) {}
