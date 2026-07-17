package com.ticketrush.boundedcontext.user.app.dto.response;

// RestClient가 result를 파싱하기 위한 DTO
public record EmailVerificationCheckResponse(boolean verified) {}
