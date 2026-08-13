package com.ticketrush.boundedcontext.performance.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * booking-service의 공통 {@code ApiResponse} 래퍼 중 이 서비스가 쓰는 필드만 매핑한다.
 *
 * <p>{@code code}를 매핑하지 않는 이유는 모든 실패가 동일하게 "매출 필드 null"로 수렴해 구분할 이유가 없기 때문이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingStatsApiResponse(BookingStatsInfo result) {}
