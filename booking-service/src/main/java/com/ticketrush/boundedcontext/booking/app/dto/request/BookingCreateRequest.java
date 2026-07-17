package com.ticketrush.boundedcontext.booking.app.dto.request;

public record BookingCreateRequest(
    Long userId, Long performanceId, Long seatId, String bookingNumber) {}
