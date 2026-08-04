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
            description =
                "마지막 환불 실패 시각. null이 아니면 환불에 실패해 예매가 유지된 것이다. "
                    + "사용자 취소 외에 좌석 확정 실패 보상 환불이 실패한 경우도 포함된다(#492).",
            example = "2026-05-23 11:00:00")
        LocalDateTime refundFailedAt,
    @Schema(
            description = "마지막 변경 시각. REFUNDING이면 환불 요청이 시작된 시각이다 (#397).",
            example = "2026-05-23 11:00:00")
        LocalDateTime updatedAt,
    @Schema(
            description =
                "PENDING 예매의 결제 마감 시각. PENDING이 아니면 null이다. 새로고침 후에도 이 값으로 카운트다운을 복원할 수 있다 (#559). "
                    + "좌석 배치 응답의 holdExpiredAt과는 값이 다르다 — 좌석 선점이 비동기라 그쪽이 더 늦으며, "
                    + "예매를 실제로 만료시키는 기준은 이 값이다.",
            example = "2026-05-22 10:35:00")
        LocalDateTime expiresAt) {

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
        booking.getUpdatedAt(),
        booking.paymentExpiresAt());
  }
}
