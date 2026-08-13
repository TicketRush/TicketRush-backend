package com.ticketrush.boundedcontext.performance.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** seat-service의 공통 {@code ApiResponse} 래퍼 중 이 서비스가 쓰는 필드만 매핑한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeatCountsApiResponse(List<SeatCountsInfo> result) {}
