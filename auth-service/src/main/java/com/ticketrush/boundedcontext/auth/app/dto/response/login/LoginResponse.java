package com.ticketrush.boundedcontext.auth.app.dto.response.login;

public record LoginResponse(
    Long userId, String email, String role, String accessToken, String refreshToken) {}
