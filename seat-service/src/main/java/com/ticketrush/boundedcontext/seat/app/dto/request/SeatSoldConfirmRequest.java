package com.ticketrush.boundedcontext.seat.app.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "좌석 판매 확정 요청 DTO")
public record SeatSoldConfirmRequest(
    @JsonProperty("booking_number")
        @Schema(
            name = "booking_number",
            description = "결제 완료된 예매 번호",
            example = "X7B29-KLPW1",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String bookingNumber,
    @JsonProperty("seat_id")
        @Schema(
            name = "seat_id",
            description = "판매 확정할 좌석 ID",
            example = "1",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Long seatId) {}
