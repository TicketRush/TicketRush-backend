package com.ticketrush.boundedcontext.seat.app.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "좌석 선점 즉시 반납 요청 DTO")
public record SeatHoldReleaseRequest(
    @JsonProperty("booking_number")
        @Schema(
            name = "booking_number",
            description = "취소된 예매 번호. 이 예매가 쥔 좌석일 때만 반납한다.",
            example = "X7B29-KLPW1",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String bookingNumber,
    @JsonProperty("seat_id")
        @Schema(
            name = "seat_id",
            description = "반납할 좌석 ID",
            example = "1",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Long seatId) {}
