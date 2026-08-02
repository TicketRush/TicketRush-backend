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
// 이 DTO는 RestClient.builder() 정적 팩토리로 만든 클라이언트가 쓰므로 앱의 JacksonConfig(SNAKE_CASE)를 타지
// 않는다. 그래서 레코드 컴포넌트명과 다른 키에만 @JsonProperty가 필요하다(붙은 것이 곧 실제로 일하는 것이다).
@JsonIgnoreProperties(ignoreUnknown = true)
public record PerformanceInfoResponse(
    String title,
    @JsonProperty("show_date") LocalDate showDate,
    @JsonProperty("show_time") LocalTime showTime,
    String address,
    Long price) {}
