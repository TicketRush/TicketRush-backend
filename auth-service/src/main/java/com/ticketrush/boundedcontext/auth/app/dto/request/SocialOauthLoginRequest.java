package com.ticketrush.boundedcontext.auth.app.dto.request;

import com.ticketrush.boundedcontext.auth.domain.types.SocialProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 클라이언트 -> 서버
public record SocialOauthLoginRequest(
    @NotNull(message = "소셜 제공자는 필수입니다.") SocialProvider provider,
    @NotBlank(message = "인가 코드는 필수입니다.") String code) {}
