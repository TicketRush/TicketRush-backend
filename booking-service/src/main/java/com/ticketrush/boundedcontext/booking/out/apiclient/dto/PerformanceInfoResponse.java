package com.ticketrush.boundedcontext.booking.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * performance-service 공연 상세 응답(PerformanceDetailResponse) 중 예매 조회 보강에 쓰는 필드만 매핑한다.
 *
 * <p>{@code price}가 결제 금액의 원본이다 — 공연당 단일가·1인 1매 확정이라 실제 결제액과 같다(#560).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PerformanceInfoResponse(
    @JsonProperty("title") String title,
    @JsonProperty("show_date") LocalDate showDate,
    @JsonProperty("show_time") LocalTime showTime,
    @JsonProperty("address") String address,
    @JsonProperty("price") Long price) {}
