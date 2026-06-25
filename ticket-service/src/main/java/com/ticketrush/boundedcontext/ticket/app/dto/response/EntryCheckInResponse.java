package com.ticketrush.boundedcontext.ticket.app.dto.response;

import com.ticketrush.boundedcontext.ticket.domain.types.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "입장 처리 응답")
public record EntryCheckInResponse(
    @Schema(description = "입장권 ID", example = "1") Long ticketId,
    @Schema(description = "입장권 상태", example = "USED") TicketStatus ticketStatus,
    @Schema(description = "입장(사용) 처리 시각", example = "2026-06-26 19:30:00") LocalDateTime usedAt) {

  public static EntryCheckInResponse of(Long ticketId, LocalDateTime usedAt) {
    return new EntryCheckInResponse(ticketId, TicketStatus.USED, usedAt);
  }
}
