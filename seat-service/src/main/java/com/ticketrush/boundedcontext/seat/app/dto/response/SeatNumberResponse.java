package com.ticketrush.boundedcontext.seat.app.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "좌석 번호 응답 DTO")
public record SeatNumberResponse(
    @Schema(description = "좌석 ID", example = "1") Long seatId,
    @Schema(description = "좌석 번호", example = "A-1") String seatNumber) {}
