package com.ticketrush.boundedcontext.auth.app.dto.response.social;

public record TokenReissueResponse(
    String accessToken,
    String refreshToken,
    Long accessTokenExpiresIn,
    Long refreshTokenExpiresIn) {}
