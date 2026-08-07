package com.ticketrush.boundedcontext.performance.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * seat-service 일괄 좌석 수 내부 API의 응답 한 행 (#563).
 *
 * <p>{@code availableCount}·{@code holdCount}는 매핑하지 않는다. 대시보드가 쓰는 것은 점유율의 분자·분모인 {@code
 * soldCount}·{@code totalCount}뿐이고, 안 쓰는 필드를 들이면 좌석 쪽 응답이 바뀔 때 이유 없이 함께 흔들린다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeatCountsInfo(
    @JsonProperty("performance_id") Long performanceId,
    @JsonProperty("total_count") long totalCount,
    @JsonProperty("sold_count") long soldCount) {}
