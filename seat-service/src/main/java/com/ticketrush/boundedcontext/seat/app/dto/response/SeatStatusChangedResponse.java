package com.ticketrush.boundedcontext.seat.app.dto.response;

import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;

public record SeatStatusChangedResponse(
    Long performanceId,
    Long seatId,
    Long seatLayoutId,
    String seatNumber,
    SeatStatus seatStatus,
    LocalDateTime holdExpiredAt) {}
