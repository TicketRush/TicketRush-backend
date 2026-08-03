package com.ticketrush.boundedcontext.user.app.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SocialLoginResponse(
    @JsonProperty("user_id") Long userId,
    String name,
    @JsonProperty("is_new_user") boolean isNewUser) {} // true = 회원가입 직후, false = 기존 유저
