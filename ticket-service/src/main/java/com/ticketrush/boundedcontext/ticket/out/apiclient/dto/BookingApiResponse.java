package com.ticketrush.boundedcontext.ticket.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** booking-service의 공통 ApiResponse 래퍼 중 ticket-service가 사용하는 필드만 매핑한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingApiResponse(
    @JsonProperty("is_success") boolean isSuccess,
    @JsonProperty("result") BookingInfoResponse result) {}
