package com.ticketrush.boundedcontext.seat.app.dto.response;

import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.global.types.SeatStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 관리자 좌석 단건 상세 응답 (#562).
 *
 * <p><b>예약자 이름이 없다.</b> 좌석은 {@code bookingNumber}만 보유하고 회원 정보를 알지 못한다. 이름을 실으려면 seat가 booking을 동기로
 * 호출해야 하는데(현재 의존은 booking→seat 단방향), 화면 한 칸 때문에 순환 의존과 booking 장애 전파 경로를 만들지 않는다. 프론트가 이 {@code
 * booking_number}로 {@code GET /api/v1/booking/admin/bookings/{bookingNumber}}를 호출해 조합한다.
 *
 * <p><b>{@code holdStartedAt}은 저장된 값이 아니라 유도값이다.</b> {@code SeatLockUseCase}가 {@code now +
 * HOLD_TTL_MINUTES}를 만료 시각으로 쓰므로 {@code holdExpiredAt - HOLD_TTL_MINUTES}가 정확한 선점 시작 시각이다. 컬럼을 더하면
 * 운영 DB에 수동 DDL이 필요하고(ADR 0003, prod는 {@code ddl-auto=validate}) 누락 시 기동이 실패하는데, 유도 가능한 값에 그 비용을
 * 치르지 않는다.
 */
@Schema(description = "관리자 좌석 단건 상세 응답 DTO")
public record SeatAdminSeatDetailResponse(
    @Schema(description = "좌석 ID", example = "100") Long seatId,
    @Schema(description = "좌석 번호", example = "A-1") String seatNumber,
    @Schema(description = "좌석 상태", example = "HOLD") SeatStatus seatStatus,
    @Schema(
            description = "이 좌석을 쥔 예매 번호. 예매자 정보를 조회할 키다. HOLD·SOLD가 아니면 null.",
            example = "X7B29-KLPW1")
        String bookingNumber,
    @Schema(
            description = "예약 시작 시간. 선점 만료 시각에서 선점 유지 시간을 뺀 값이며 HOLD가 아니면 null.",
            example = "2026-05-22 10:30:00")
        LocalDateTime holdStartedAt,
    @Schema(description = "예약 만료 시각. HOLD가 아니면 null.", example = "2026-05-22 10:35:00")
        LocalDateTime holdExpiredAt,
    @Schema(
            description = "남은 예약 시간(초). 이미 만료됐거나 HOLD가 아니면 0. 화면 타이머는 이 값을 기준으로 센다.",
            example = "212")
        long remainingSeconds) {

  public static SeatAdminSeatDetailResponse of(Seat seat, LocalDateTime now) {
    boolean held = seat.getSeatStatus() == SeatStatus.HOLD;
    LocalDateTime expiredAt = held ? seat.getHoldExpiredAt() : null;

    return new SeatAdminSeatDetailResponse(
        seat.getId(),
        seat.getSeatNumber(),
        seat.getSeatStatus(),
        seat.getBookingNumber(),
        expiredAt == null ? null : expiredAt.minusMinutes(Seat.HOLD_TTL_MINUTES),
        expiredAt,
        remainingSeconds(expiredAt, now));
  }

  /**
   * 만료가 지난 HOLD는 0으로 눌러 내린다. 만료 해제는 Redis 키 만료 이벤트나 60초 스케줄러가 하므로 좌석이 잠시 HOLD인 채 만료 시각을 지나 있을 수
   * 있는데, 음수를 그대로 내리면 화면 타이머가 거꾸로 흐른다.
   */
  private static long remainingSeconds(LocalDateTime expiredAt, LocalDateTime now) {
    if (expiredAt == null) {
      return 0L;
    }
    return Math.max(0L, Duration.between(now, expiredAt).toSeconds());
  }
}
