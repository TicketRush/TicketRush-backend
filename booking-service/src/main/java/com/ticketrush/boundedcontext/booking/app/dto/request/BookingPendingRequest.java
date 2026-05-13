package com.ticketrush.boundedcontext.booking.app.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "예약 대기 생성 요청 DTO")
public record BookingPendingRequest(
    @JsonProperty("performance_id")
        @Schema(
            name = "performance_id",
            description = "공연 ID",
            example = "1",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Long performanceId,
    @JsonProperty("seat_id")
        @Schema(
            name = "seat_id",
            description = "좌석 ID",
            example = "1",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Long seatId) {}
