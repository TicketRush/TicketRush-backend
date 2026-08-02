package com.ticketrush.boundedcontext.booking.app.dto.response;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceInfoResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 예매 단건 상세 응답 (#560). 공연·좌석 필드는 각 서비스 조회 실패 시 null(응답에서 생략)일 수 있다 — 그때 프론트는 {@code
 * performanceId}/{@code seatId}로 재조회한다.
 */
@Schema(description = "예매 단건 상세 응답 DTO")
public record BookingDetailResponse(
    @Schema(
            description = "예매 ID. 입장권 QR 조회(GET /api/v1/ticket/bookings/{bookingId}/qr)의 키다.",
            example = "1")
        Long bookingId,
    @Schema(description = "클라이언트에게 노출되는 주문/예약 번호", example = "X7B29-KLPW1") String bookingNumber,
    @Schema(description = "예매 상태", example = "CONFIRMED") BookingStatus bookingStatus,
    @Schema(description = "공연 ID. 공연 필드가 비어 있을 때 재조회 키다.", example = "10") Long performanceId,
    @Schema(description = "공연 이름. performance-service 장애 시 null.", example = "오페라의 유령")
        String performanceTitle,
    @Schema(description = "공연 날짜. performance-service 장애 시 null.", example = "2026-05-22")
        LocalDate performanceDate,
    @Schema(description = "공연 시간. performance-service 장애 시 null.", example = "19:30:00")
        LocalTime performanceTime,
    @Schema(description = "공연 장소. performance-service 장애 시 null.", example = "서울 예술의전당 오페라극장")
        String performanceAddress,
    @Schema(description = "좌석 ID. 좌석 번호가 비어 있을 때 재조회 키다.", example = "100") Long seatId,
    @Schema(description = "좌석 번호. seat-service 장애 시 null.", example = "A-1") String seatNumber,
    @Schema(description = "예매 확정일", example = "2026-05-22 10:30:00") LocalDateTime confirmedAt,
    @Schema(
            description = "결제 금액. 공연당 단일가·1인 1매라 공연 가격과 같다. performance-service 장애 시 null.",
            example = "150000")
        Long paymentAmount) {

  /** {@code performance}와 {@code seatNumber}는 조회 실패 시 null이 허용된다(부분 응답). */
  public static BookingDetailResponse of(
      Booking booking, PerformanceInfoResponse performance, String seatNumber) {
    return new BookingDetailResponse(
        booking.getId(),
        booking.getBookingNumber(),
        booking.getBookingStatus(),
        booking.getPerformanceId(),
        (performance == null) ? null : performance.title(),
        (performance == null) ? null : performance.showDate(),
        (performance == null) ? null : performance.showTime(),
        (performance == null) ? null : performance.address(),
        booking.getSeatId(),
        seatNumber,
        booking.getConfirmedAt(),
        (performance == null) ? null : performance.price());
  }
}
