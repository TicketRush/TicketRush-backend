package com.ticketrush.boundedcontext.ticket.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** booking-service internal 조회 API 응답의 result 본문(snake_case JSON)을 매핑한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingInfoResponse(
    @JsonProperty("booking_id") Long bookingId,
    @JsonProperty("user_id") Long userId,
    @JsonProperty("booking_status") String bookingStatus) {}
