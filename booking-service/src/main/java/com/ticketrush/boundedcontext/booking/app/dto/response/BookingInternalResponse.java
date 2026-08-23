package com.ticketrush.boundedcontext.booking.app.dto.response;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 예매 내부 조회 응답 (#490).
 *
 * <p>{@code bookingNumber}는 payment 가 좌석 소유 교차검증(ABA 방지)을 위해 쓴다(#607). 환불 경로가 발행하는 {@code
 * PaymentCanceledEvent}에 이 값이 실려야 seat 가 "그 좌석이 아직 이 예매의 것인지"를 대조할 수 있고, null 로 나가면 그 대조가 통째로 꺼진다.
 * payment 는 자신의 테이블에 예매번호를 갖고 있지 않아 이 조회로만 얻는다.
 */
@Schema(description = "예매 내부 조회 응답(서비스 간 통신 전용)")
public record BookingInternalResponse(
    @Schema(description = "예매 ID", example = "100") Long bookingId,
    @Schema(description = "예매 소유자 ID", example = "10") Long userId,
    @Schema(description = "예매 상태", example = "CONFIRMED") BookingStatus bookingStatus,
    @Schema(description = "예매 번호", example = "BK20260817000001") String bookingNumber) {

  public static BookingInternalResponse from(Booking booking) {
    return new BookingInternalResponse(
        booking.getId(),
        booking.getUserId(),
        booking.getBookingStatus(),
        booking.getBookingNumber());
  }
}
