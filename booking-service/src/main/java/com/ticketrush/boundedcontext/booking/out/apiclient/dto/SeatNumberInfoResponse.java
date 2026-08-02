package com.ticketrush.boundedcontext.booking.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** seat-service 좌석 번호 벌크 조회(SeatNumberResponse) 응답 항목. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeatNumberInfoResponse(
    @JsonProperty("seat_id") Long seatId, @JsonProperty("seat_number") String seatNumber) {}
