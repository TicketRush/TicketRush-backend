package com.ticketrush.boundedcontext.ticket.app.dto.response;

import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "입장권 내부 조회 응답(서비스 간 통신 전용)")
public record TicketInternalResponse(
    @Schema(description = "예매 ID", example = "100") Long bookingId,
    @Schema(description = "입장권 상태", example = "UNUSED") TicketStatus ticketStatus) {}
