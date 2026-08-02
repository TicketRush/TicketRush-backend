package com.ticketrush.boundedcontext.booking.app.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/** seat-service 좌석 선점 즉시 반납 API(POST /api/v1/internal/seat/release) 요청 바디 (#559). */
public record SeatHoldReleaseRequest(
    @JsonProperty("booking_number") String bookingNumber, @JsonProperty("seat_id") Long seatId) {}
