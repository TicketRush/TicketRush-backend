package com.ticketrush.boundedcontext.performance.app.dto.request;

import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import jakarta.validation.constraints.NotNull;

public record PerformanceChangeStatusRequest(
    @NotNull(message = "변경할 상태는 필수입니다.") PerformanceStatus status) {}
