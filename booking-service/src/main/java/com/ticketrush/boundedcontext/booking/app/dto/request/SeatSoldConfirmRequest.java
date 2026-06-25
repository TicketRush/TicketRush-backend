package com.ticketrush.boundedcontext.booking.app.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/** seat-service 좌석 판매 확정 API(POST /api/v1/seat/internal/sold) 요청 바디. */
public record SeatSoldConfirmRequest(
    @JsonProperty("booking_number") String bookingNumber, @JsonProperty("seat_id") Long seatId) {}
