package com.ticketrush.boundedcontext.auth.app.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record TokenReissueRequest(
    @JsonProperty("refreshToken") @NotBlank(message = "리프레시 토큰은 필수입니다.") String refreshToken) {}
