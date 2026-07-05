package com.ticketrush.boundedcontext.ticket.app.dto.response;

import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "입장권 QR payload 조회 응답")
public record TicketQrResponse(
    @Schema(
            description = "QR로 렌더링할 서명 payload(JWT)",
            example = "eyJhbGciOiJIUzI1NiJ9.eyJ0aWQiOjF9...")
        String payload,
    @Schema(description = "입장권 상태", example = "UNUSED") TicketStatus ticketStatus,
    @Schema(description = "입장권 발급 시각", example = "2026-06-25 10:00:00") LocalDateTime issuedAt,
    @Schema(description = "QR payload 만료 시각", example = "2026-06-25 10:05:00")
        LocalDateTime expiresAt) {}
