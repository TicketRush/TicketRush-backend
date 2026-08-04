package com.ticketrush.boundedcontext.booking.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** user-service의 공통 ApiResponse 래퍼 중 booking-service가 사용하는 필드만 매핑한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSummariesApiResponse(List<UserSummaryInfoResponse> result) {}
