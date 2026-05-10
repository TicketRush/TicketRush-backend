package com.ticketrush.boundedcontext.auth.app.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/** auth-service가 인가 코드로 카카오에 토큰을 요청했을 때, 카카오가 토큰 응답 반환 DTO 카카오 → 우리 서버로 오는 응답 DTO */
public record OauthTokenResponse(
    @JsonProperty("token_type") String tokenType, // ex) bearer
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("refresh_token") String refreshToken,
    @JsonProperty("expires_in") Long expiresIn, // access token 만료 시간, second 단위
    @JsonProperty("refresh_token_expires_in") Long refreshTokenExpiresIn) {}
