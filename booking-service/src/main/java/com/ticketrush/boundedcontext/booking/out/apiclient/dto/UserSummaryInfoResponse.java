package com.ticketrush.boundedcontext.booking.out.apiclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * user-service 회원 요약 벌크 조회(UserSummaryResponse) 응답 항목 (#561).
 *
 * <p>{@code name}·{@code email}은 user-service에서 nullable이라(소셜 가입 경로) null로 올 수 있다.
 */
// 이 DTO는 RestClient.builder() 정적 팩토리로 만든 클라이언트가 쓰므로 앱의 JacksonConfig(SNAKE_CASE)를 타지
// 않는다. 그래서 레코드 컴포넌트명과 다른 키에만 @JsonProperty가 필요하다(붙은 것이 곧 실제로 일하는 것이다).
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSummaryInfoResponse(
    @JsonProperty("user_id") Long userId, String name, String email) {}
