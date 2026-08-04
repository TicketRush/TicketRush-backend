package com.ticketrush.boundedcontext.booking.app.dto.response;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceInfoResponse;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.UserSummaryInfoResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 관리자 예매 목록 응답 (#561). 목록 컬럼과 행 확장 드롭다운(예매자 정보·좌석 정보·좌석 수·단가·총액)을 한 응답에 함께 싣는다 — 1인 1매라 드롭다운 필드가 목록
 * 필드의 부분집합이고 단가와 총액이 같은 값이므로, 별도 상세 엔드포인트를 두면 같은 데이터를 두 번 내리게 된다.
 *
 * <p>{@link BookingSummaryResponse}(환불 관리자 조회 2개와 공유)를 보강하지 않고 별도로 둔다 — 공유 DTO를 건드리면 enrichment가 필요
 * 없는 그쪽 응답까지 바뀐다. 회원 목록이 {@link BookingMySummaryResponse}로 갈라진 것과 같은 이유다.
 *
 * <p>보강 필드는 각 서비스 조회 실패 시 null(응답에서 생략)일 수 있다 — 프론트는 {@code performanceId}/{@code userId}/{@code
 * seatId}로 재조회한다.
 */
@Schema(description = "관리자 예매 목록 응답 DTO")
public record BookingAdminSummaryResponse(
    @Schema(description = "예매 ID", example = "1") Long bookingId,
    @Schema(description = "예매 번호", example = "X7B29-KLPW1") String bookingNumber,
    @Schema(description = "예매자 회원 ID. 예매자 필드가 비어 있을 때 재조회 키다.", example = "5") Long userId,
    @Schema(description = "공연 ID. 공연 필드가 비어 있을 때 재조회 키다.", example = "10") Long performanceId,
    @Schema(description = "좌석 ID. 좌석 번호가 비어 있을 때 재조회 키다.", example = "100") Long seatId,
    @Schema(description = "예매 상태", example = "CONFIRMED") BookingStatus bookingStatus,
    @Schema(description = "예매 일시", example = "2026-05-22 10:30:00") LocalDateTime bookedAt,
    @Schema(description = "공연 이름. performance-service 장애 시 null.", example = "오페라의 유령")
        String performanceTitle,
    @Schema(description = "공연 날짜. performance-service 장애 시 null.", example = "2026-05-22")
        LocalDate performanceDate,
    @Schema(description = "예매자 이름. user-service 장애 시, 또는 소셜 가입 회원이면 null.", example = "김소희")
        String bookerName,
    @Schema(
            description = "예매자 이메일. user-service 장애 시, 또는 소셜 가입 회원이면 null.",
            example = "user@example.com")
        String bookerEmail,
    @Schema(description = "좌석 번호. seat-service 장애 시 null.", example = "A-1") String seatNumber,
    @Schema(description = "좌석 수. 1인 1매라 항상 1이다.", example = "1") int seatCount,
    @Schema(
            description =
                "결제 금액. 1인 1매·공연당 단일가라 티켓 단가와 총 결제 금액이 같은 값이다. performance-service 장애 시 null. "
                    + "출처가 현재 공연 가격이므로, 예매 후 관리자가 가격을 바꾸면 이 값도 함께 바뀐다 — "
                    + "실제 결제액의 SSOT는 payment-service다.",
            example = "150000")
        Long paymentAmount) {

  /** 1인 1매 확정 (#561). 다매가 도입되면 booking에 수량 컬럼이 생기고 여기가 그 값을 읽는다. */
  private static final int SEAT_COUNT_PER_BOOKING = 1;

  /** {@code performance}·{@code seatNumber}·{@code user}는 조회 실패 시 null이 허용된다(부분 응답). */
  public static BookingAdminSummaryResponse of(
      Booking booking,
      PerformanceInfoResponse performance,
      String seatNumber,
      UserSummaryInfoResponse user) {
    return new BookingAdminSummaryResponse(
        booking.getId(),
        booking.getBookingNumber(),
        booking.getUserId(),
        booking.getPerformanceId(),
        booking.getSeatId(),
        booking.getBookingStatus(),
        booking.getCreatedAt(),
        (performance == null) ? null : performance.title(),
        (performance == null) ? null : performance.showDate(),
        (user == null) ? null : user.name(),
        (user == null) ? null : user.email(),
        seatNumber,
        SEAT_COUNT_PER_BOOKING,
        (performance == null) ? null : performance.price());
  }
}
