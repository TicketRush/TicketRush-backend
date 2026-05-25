package com.ticketrush.boundedcontext.booking.app.dto.response;

import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원 예매 수 응답 DTO")
public record BookingCountResponse(
    @Schema(description = "집계 대상 예매 상태", example = "CONFIRMED") BookingStatus bookingStatus,
    @Schema(description = "예매 수", example = "3") long count) {}
