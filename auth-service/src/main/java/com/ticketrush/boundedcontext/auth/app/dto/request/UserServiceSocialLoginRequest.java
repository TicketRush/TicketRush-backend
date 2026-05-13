package com.ticketrush.boundedcontext.auth.app.dto.request;

// auth → user-service 요청 DTO
public record UserServiceSocialLoginRequest(
    String socialId, String socialProvider, String name, String email) {}
