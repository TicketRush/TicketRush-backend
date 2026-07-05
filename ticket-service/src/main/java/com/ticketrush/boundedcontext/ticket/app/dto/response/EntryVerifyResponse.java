package com.ticketrush.boundedcontext.ticket.app.dto.response;

import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "QR 토큰 검증 응답(입장 가능 상태)")
public record EntryVerifyResponse(
    @Schema(description = "입장권 ID", example = "1") Long ticketId,
    @Schema(description = "예매 ID", example = "100") Long bookingId,
    @Schema(description = "입장권 상태", example = "UNUSED") TicketStatus ticketStatus) {}
