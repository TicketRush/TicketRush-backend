package com.ticketrush.boundedcontext.booking.app.dto.response;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.domain.types.RefundProcessStatus;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.PerformanceInfoResponse;
import com.ticketrush.boundedcontext.booking.out.apiclient.dto.UserSummaryInfoResponse;
import com.ticketrush.global.json.UtcLocalDateTimeSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 관리자 환불 통합 목록 응답 (#675). 환불 진행·완료·미해결 실패를 한 목록에 담는다.
 *
 * <p>{@link BookingAdminSummaryResponse}를 재사용하지 않고 별도로 둔다 — 그 DTO는 환불 화면에 없는 좌석 번호·좌석 수·예매자 이메일을
 * 싣고, 거기에 환불 처리 상태를 더하면 환불과 무관한 기존 두 응답(목록·단건)까지 함께 바뀐다. 필드를 줄인 만큼 <b>이 조회는 seat-service를 호출하지
 * 않는다</b>.
 *
 * <p><b>시각 규약이 두 가지다.</b> {@code bookedAt}·{@code refundFailedAt}은 UTC 순간이고, {@code
 * performanceDate}·{@code performanceTime}은 Asia/Seoul 벽시계다(ADR 0020). 공연 일시를 UTC로 읽으면 9시간 어긋난다.
 *
 * <p>보강 필드는 각 서비스 조회 실패 시 null(응답에서 생략)일 수 있다 — 프론트는 {@code performanceId}/{@code userId}로 재조회한다.
 */
@Schema(description = "관리자 환불 목록 응답 DTO")
public record BookingAdminRefundSummaryResponse(
    @Schema(description = "예매 ID", example = "1") Long bookingId,
    @Schema(description = "예매 번호", example = "X7B29-KLPW1") String bookingNumber,
    @Schema(description = "예매자 회원 ID. 예매자 필드가 비어 있을 때 재조회 키다.", example = "5") Long userId,
    @Schema(description = "공연 ID. 공연 필드가 비어 있을 때 재조회 키다.", example = "10") Long performanceId,
    @Schema(description = "예매 상태. 환불 처리 상태와 다른 축이다.", example = "CONFIRMED")
        BookingStatus bookingStatus,
    @Schema(
            description =
                "환불 처리 상태. 예매 상태와 실패 시각에서 파생하며 저장되지 않는다. "
                    + "REFUNDING이면 IN_PROGRESS, REFUNDED이면 COMPLETED, "
                    + "CONFIRMED이면서 환불 실패 이력이 있으면 FAILED다. "
                    + "**행 분류의 기준은 이 값 하나다** — refund_failed_at으로 분류하면 재시도 중인 건이 실패로 잡힌다.",
            example = "FAILED")
        RefundProcessStatus refundStatus,
    @Schema(description = "예매 일시", example = "2026-05-22T10:30:00Z")
        @JsonSerialize(using = UtcLocalDateTimeSerializer.class)
        LocalDateTime bookedAt,
    @Schema(
            description =
                "마지막 환불 실패 시각. **이력일 뿐이며 현재 상태가 아니다** — 재환불 시 지워지지 않으므로 "
                    + "refund_status가 IN_PROGRESS·COMPLETED인 행에도 값이 남아 있을 수 있다. "
                    + "환불에 실패한 적이 없으면 null이다.",
            example = "2026-05-23T11:00:00Z")
        @JsonSerialize(using = UtcLocalDateTimeSerializer.class)
        LocalDateTime refundFailedAt,
    @Schema(description = "공연 이름. performance-service 장애 시 null.", example = "오페라의 유령")
        String performanceTitle,
    @Schema(
            description = "공연 날짜(Asia/Seoul 벽시계). performance-service 장애 시 null.",
            example = "2026-05-22")
        LocalDate performanceDate,
    @Schema(
            description = "공연 시작 시각(Asia/Seoul 벽시계). performance-service 장애 시 null.",
            example = "19:30:00")
        LocalTime performanceTime,
    @Schema(description = "예매자 이름. user-service 장애 시, 또는 소셜 가입 회원이면 null.", example = "김소희")
        String bookerName,
    @Schema(
            description =
                "결제 금액. 예매가 확정 시점에 보유한 **실제 결제액**이며 1인 1매라 티켓 단가와 총액이 같다. "
                    + "**PG에서 확정된 실제 환불액이 아니다** — 부분 환불·수수료 차감은 이 값에 반영되지 않는다. "
                    + "결제 금액이 기록되지 않은 예매는 null이며, 그 경우에도 행은 그대로 내려간다.",
            example = "150000")
        Long paymentAmount) {

  /** {@code performance}·{@code user}는 조회 실패 시 null이 허용된다(부분 응답). */
  public static BookingAdminRefundSummaryResponse of(
      Booking booking, PerformanceInfoResponse performance, UserSummaryInfoResponse user) {
    return new BookingAdminRefundSummaryResponse(
        booking.getId(),
        booking.getBookingNumber(),
        booking.getUserId(),
        booking.getPerformanceId(),
        booking.getBookingStatus(),
        RefundProcessStatus.from(booking.getBookingStatus(), booking.getRefundFailedAt()),
        booking.getCreatedAt(),
        booking.getRefundFailedAt(),
        (performance == null) ? null : performance.title(),
        (performance == null) ? null : performance.showDate(),
        (performance == null) ? null : performance.showTime(),
        (user == null) ? null : user.name(),
        booking.getPaidAmount());
  }
}
