package com.ticketrush.boundedcontext.booking.app.dto.response;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "예약 대기 상태 응답 DTO")
public record BookingPendingResponse(
    @Schema(description = "예약 데이터의 고유 식별자(PK)", example = "1") Long bookingId,
    @Schema(description = "클라이언트에게 노출되는 주문/예약 번호", example = "X7B29-KLPW1") String bookingNumber,
    @Schema(description = "현재 예약 상태 (대기 상태이므로 항상 PENDING 반환)", example = "PENDING") String status) {

  public static BookingPendingResponse from(Booking booking) {
    return new BookingPendingResponse(
        booking.getId(), booking.getBookingNumber(), booking.getBookingStatus().name());
  }
}
