package com.ticketrush.boundedcontext.booking.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * performance-service의 공통 ApiResponse 래퍼 중 booking-service가 사용하는 필드만 매핑한다.
 *
 * <p>{@code TicketApiResponse}와 달리 {@code code}를 매핑하지 않는다 — 그쪽은 404를 "입장권 미발급"으로 해석해도 되는지 가리는 데 코드가
 * 필요했지만, 여기서는 모든 실패가 동일하게 빈 결과로 수렴해 구분할 이유가 없다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PerformanceApiResponse(PerformanceInfoResponse result) {}
