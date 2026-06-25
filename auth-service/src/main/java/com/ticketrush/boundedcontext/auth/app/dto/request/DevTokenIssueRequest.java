package com.ticketrush.boundedcontext.auth.app.dto.request;

import jakarta.validation.constraints.NotNull;

public record DevTokenIssueRequest(@NotNull(message = "userId는 필수입니다.") Long userId) {}
