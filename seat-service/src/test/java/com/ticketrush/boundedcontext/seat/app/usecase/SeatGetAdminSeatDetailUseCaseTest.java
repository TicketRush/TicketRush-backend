package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAdminSeatDetailResponse;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatGetAdminSeatDetailUseCaseTest {

  private static final String BOOKING_NUMBER = "X7B29-KLPW1";
  private static final Long PERFORMANCE_ID = 1L;
  private static final Long SEAT_ID = 100L;

  @Mock private SeatRepository seatRepository;

  @InjectMocks private SeatGetAdminSeatDetailUseCase seatGetAdminSeatDetailUseCase;

  private Seat seat(SeatStatus status, LocalDateTime holdExpiredAt, String bookingNumber) {
    return Seat.builder()
        .seatLayoutId(1L)
        .performanceId(PERFORMANCE_ID)
        .seatNumber("A-1")
        .seatStatus(status)
        .holdExpiredAt(holdExpiredAt)
        .bookingNumber(bookingNumber)
        .build();
  }

  @Test
  @DisplayName("성공: HOLD 좌석은 선점 시작 시각과 남은 시간을 함께 응답한다")
  void execute_returns_hold_window_for_held_seat() {
    // given
    LocalDateTime expiredAt = LocalDateTime.of(2026, 8, 2, 10, 35);
    given(seatRepository.findByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(Optional.of(seat(SeatStatus.HOLD, expiredAt, BOOKING_NUMBER)));

    // when
    SeatAdminSeatDetailResponse response =
        seatGetAdminSeatDetailUseCase.execute(PERFORMANCE_ID, SEAT_ID);

    // then
    assertThat(response.seatNumber()).isEqualTo("A-1");
    assertThat(response.seatStatus()).isEqualTo(SeatStatus.HOLD);
    assertThat(response.bookingNumber()).isEqualTo(BOOKING_NUMBER);
    assertThat(response.holdExpiredAt()).isEqualTo(expiredAt);
  }

  @Test
  @DisplayName("성공: 선점 시작 시각은 만료 시각에서 선점 유지 시간을 뺀 값이다 (컬럼 없이 유도)")
  void of_derives_hold_started_at_from_expiry() {
    // given: Seat.HOLD_TTL_MINUTES가 SeatLockUseCase의 TTL과 같은 값이라 유도가 성립한다
    LocalDateTime expiredAt = LocalDateTime.of(2026, 8, 2, 10, 35);
    LocalDateTime now = LocalDateTime.of(2026, 8, 2, 10, 31, 28);

    // when
    SeatAdminSeatDetailResponse response =
        SeatAdminSeatDetailResponse.of(seat(SeatStatus.HOLD, expiredAt, BOOKING_NUMBER), now);

    // then
    assertThat(response.holdStartedAt()).isEqualTo(LocalDateTime.of(2026, 8, 2, 10, 30));
    assertThat(response.remainingSeconds()).isEqualTo(212L);
  }

  @Test
  @DisplayName("성공: 만료 시각이 지난 HOLD의 남은 시간은 0으로 눌러 내린다")
  void of_clamps_remaining_seconds_at_zero() {
    // given: 만료 해제는 Redis 만료 이벤트나 60초 스케줄러가 하므로 잠시 HOLD인 채 만료를 지날 수 있다.
    // 음수를 그대로 내리면 화면 타이머가 거꾸로 흐른다.
    LocalDateTime expiredAt = LocalDateTime.of(2026, 8, 2, 10, 35);
    LocalDateTime now = LocalDateTime.of(2026, 8, 2, 10, 36);

    // when
    SeatAdminSeatDetailResponse response =
        SeatAdminSeatDetailResponse.of(seat(SeatStatus.HOLD, expiredAt, BOOKING_NUMBER), now);

    // then
    assertThat(response.remainingSeconds()).isZero();
    assertThat(response.holdExpiredAt()).isEqualTo(expiredAt);
  }

  @Test
  @DisplayName("성공: HOLD가 아닌 좌석은 선점 관련 필드가 모두 비어 있다")
  void of_leaves_hold_fields_null_for_non_held_seat() {
    // given: 판매 완료 좌석은 booking_number를 유지하지만(멱등 판정 근거) 선점 창은 없다
    LocalDateTime now = LocalDateTime.of(2026, 8, 2, 10, 36);

    // when
    SeatAdminSeatDetailResponse response =
        SeatAdminSeatDetailResponse.of(seat(SeatStatus.SOLD, null, BOOKING_NUMBER), now);

    // then
    assertThat(response.seatStatus()).isEqualTo(SeatStatus.SOLD);
    assertThat(response.bookingNumber()).isEqualTo(BOOKING_NUMBER);
    assertThat(response.holdStartedAt()).isNull();
    assertThat(response.holdExpiredAt()).isNull();
    assertThat(response.remainingSeconds()).isZero();
  }

  @Test
  @DisplayName("거절: 다른 공연의 좌석 ID는 404로 거절한다")
  void execute_rejects_seat_of_other_performance() {
    // given: 경로의 performanceId를 검증하지 않으면 임의 공연 경로로 남의 좌석을 조회할 수 있다
    given(seatRepository.findByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> seatGetAdminSeatDetailUseCase.execute(PERFORMANCE_ID, SEAT_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.SEAT_NOT_FOUND);
  }
}
