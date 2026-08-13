package com.ticketrush.boundedcontext.payment.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * booking-service의 공통 ApiResponse 래퍼 중 payment-service가 사용하는 필드만 매핑한다.
 *
 * <p>{@code code}는 성공 응답뿐 아니라 <b>에러 응답 본문</b>에도 실린다. 404를 "예매 없음"으로 해석해도 되는지 가리는 데 쓴다 — 경로 오설정 등으로
 * 발생한 404는 이 코드가 없거나 다르다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingApiResponse(
    @JsonProperty("is_success") boolean isSuccess,
    @JsonProperty("code") String code,
    @JsonProperty("result") BookingInfoResponse result) {}
