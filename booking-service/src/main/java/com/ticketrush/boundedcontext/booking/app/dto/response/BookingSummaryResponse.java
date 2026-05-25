package com.ticketrush.boundedcontext.booking.app.dto.response;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "회원 예매 요약 응답 DTO")
public record BookingSummaryResponse(
    @Schema(description = "예매 ID", example = "1") Long bookingId,
    @Schema(description = "클라이언트에게 노출되는 주문/예약 번호", example = "X7B29-KLPW1") String bookingNumber,
    @Schema(description = "공연 ID", example = "10") Long performanceId,
    @Schema(description = "좌석 ID", example = "100") Long seatId,
    @Schema(description = "예매 상태", example = "CONFIRMED") BookingStatus bookingStatus,
    @Schema(description = "예매 확정일", example = "2026-05-22 10:30:00") LocalDateTime confirmedAt) {

  public static BookingSummaryResponse from(Booking booking) {
    return new BookingSummaryResponse(
        booking.getId(),
        booking.getBookingNumber(),
        booking.getPerformanceId(),
        booking.getSeatId(),
        booking.getBookingStatus(),
        booking.getConfirmedAt());
  }
}
