package com.ticketrush.boundedcontext.seat.app.dto.response;

import com.ticketrush.global.json.UtcLocalDateTimeSerializer;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

public record SeatStatusChangedResponse(
    Long performanceId,
    Long seatId,
    Long seatLayoutId,
    String seatNumber,
    SeatStatus seatStatus,
    @JsonSerialize(using = UtcLocalDateTimeSerializer.class) LocalDateTime holdExpiredAt) {}
