package com.ticketrush.boundedcontext.user.app.dto.response;

public record SocialLoginResponse(
    Long userId, String name, boolean isNewUser) {} // true = 회원가입 직후, false = 기존 유저
