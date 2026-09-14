package com.ticketrush.boundedcontext.ticket.app.dto.response;

import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import com.ticketrush.global.json.UtcLocalDateTimeSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

@Schema(description = "입장권 QR payload 조회 응답")
public record TicketQrResponse(
    @Schema(
            description = "QR로 렌더링할 서명 payload(JWT)",
            example = "eyJhbGciOiJIUzI1NiJ9.eyJ0aWQiOjF9...")
        String payload,
    @Schema(description = "입장권 상태", example = "UNUSED") TicketStatus ticketStatus,
    @Schema(description = "입장권 발급 시각", example = "2026-06-25T10:00:00Z")
        @JsonSerialize(using = UtcLocalDateTimeSerializer.class)
        LocalDateTime issuedAt,
    @Schema(description = "QR payload 만료 시각", example = "2026-06-25T10:05:00Z")
        @JsonSerialize(using = UtcLocalDateTimeSerializer.class)
        LocalDateTime expiresAt) {}
