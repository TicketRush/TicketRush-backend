package com.ticketrush.boundedcontext.auth.app.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SignupEmailAuthNumberVerifyRequest(
    @NotBlank(message = "이메일은 필수입니다.") @Email(message = "이메일 형식이 올바르지 않습니다.") String email,
    @JsonAlias("authNumber") @NotBlank(message = "인증번호는 필수입니다.") String authNumber) {}
