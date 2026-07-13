package com.ticketrush.boundedcontext.booking.app.dto.response;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "회원 예매 요약 응답 DTO")
public record BookingSummaryResponse(
    @Schema(description = "예매 ID", example = "1") Long bookingId,
    @Schema(description = "클라이언트에게 노출되는 주문/예약 번호", example = "X7B29-KLPW1") String bookingNumber,
    @Schema(description = "회원 ID", example = "5") Long userId,
    @Schema(description = "공연 ID", example = "10") Long performanceId,
    @Schema(description = "좌석 ID", example = "100") Long seatId,
    @Schema(description = "예매 상태", example = "CONFIRMED") BookingStatus bookingStatus,
    @Schema(description = "예매 확정일", example = "2026-05-22 10:30:00") LocalDateTime confirmedAt,
    @Schema(
            description = "마지막 환불 실패 시각. null이 아니면 취소를 요청했으나 환불에 실패해 예매가 유지된 것이다.",
            example = "2026-05-23 11:00:00")
        LocalDateTime refundFailedAt,
    @Schema(
            description = "마지막 변경 시각. REFUNDING이면 환불 요청이 시작된 시각이다 (#397).",
            example = "2026-05-23 11:00:00")
        LocalDateTime updatedAt) {

  public static BookingSummaryResponse from(Booking booking) {
    return new BookingSummaryResponse(
        booking.getId(),
        booking.getBookingNumber(),
        booking.getUserId(),
        booking.getPerformanceId(),
        booking.getSeatId(),
        booking.getBookingStatus(),
        booking.getConfirmedAt(),
        booking.getRefundFailedAt(),
        booking.getUpdatedAt());
  }
}
