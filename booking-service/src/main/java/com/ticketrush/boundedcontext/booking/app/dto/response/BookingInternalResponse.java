package com.ticketrush.boundedcontext.booking.app.dto.response;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "예매 내부 조회 응답(서비스 간 통신 전용)")
public record BookingInternalResponse(
    @Schema(description = "예매 ID", example = "100") Long bookingId,
    @Schema(description = "예매 소유자 ID", example = "10") Long userId,
    @Schema(description = "예매 상태", example = "CONFIRMED") BookingStatus bookingStatus) {

  public static BookingInternalResponse from(Booking booking) {
    return new BookingInternalResponse(
        booking.getId(), booking.getUserId(), booking.getBookingStatus());
  }
}
