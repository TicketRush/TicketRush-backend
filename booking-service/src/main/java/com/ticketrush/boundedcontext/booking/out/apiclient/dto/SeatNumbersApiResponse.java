package com.ticketrush.boundedcontext.booking.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** seat-service의 공통 ApiResponse 래퍼 중 booking-service가 사용하는 필드만 매핑한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeatNumbersApiResponse(@JsonProperty("result") List<SeatNumberInfoResponse> result) {}
