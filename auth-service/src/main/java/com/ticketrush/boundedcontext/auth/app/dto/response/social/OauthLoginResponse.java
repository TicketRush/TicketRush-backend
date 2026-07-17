package com.ticketrush.boundedcontext.auth.app.dto.response.social;

public record OauthLoginResponse(
    Long userId,
    String name,
    Boolean isNewUser,
    String accessToken,
    String refreshToken,
    Long accessTokenExpiresIn,
    Long refreshTokenExpiresIn) {}
