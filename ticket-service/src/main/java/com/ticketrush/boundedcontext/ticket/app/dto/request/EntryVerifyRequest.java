package com.ticketrush.boundedcontext.ticket.app.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "QR 토큰 검증/입장 처리 요청")
public record EntryVerifyRequest(
    @Schema(description = "스캔한 QR payload(JWT)", example = "eyJhbGciOiJIUzI1NiJ9.eyJ0aWQiOjF9...")
        @NotBlank(message = "QR 토큰은 필수입니다.")
        String token) {}
