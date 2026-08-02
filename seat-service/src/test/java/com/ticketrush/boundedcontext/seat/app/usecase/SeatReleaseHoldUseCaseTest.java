package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.seat.app.support.SeatEventSource;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
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
class SeatReleaseHoldUseCaseTest {

  private static final String BOOKING_NUMBER = "X7B29-KLPW1";

  @Mock private SeatRepository seatRepository;
  @Mock private SeatStatusEventPublisher seatStatusEventPublisher;
  @Mock private SeatUnlockUseCase seatUnlockUseCase;

  @InjectMocks private SeatReleaseHoldUseCase seatReleaseHoldUseCase;

  private Seat holdSeat(LocalDateTime holdExpiredAt, String bookingNumber) {
    return Seat.builder()
        .seatLayoutId(1L)
        .performanceId(1L)
        .seatNumber("A-1")
        .seatStatus(SeatStatus.HOLD)
        .holdExpiredAt(holdExpiredAt)
        .bookingNumber(bookingNumber)
        .build();
  }

  @Test
  @DisplayName("성공: 아직 만료되지 않은 HOLD도 즉시 AVAILABLE로 반납한다 (#559)")
  void execute_WhenHoldNotYetExpired_StillReleases() {
    // given: 만료까지 4분이 남은 선점. 즉시 취소는 정의상 '만료 전' 좌석을 되돌리는 것이므로
    // 조건부 UPDATE 가드에 now() 비교가 없어야 이 케이스가 통과한다.
    Long seatId = 1L;
    LocalDateTime notExpiredYet = LocalDateTime.now().plusMinutes(4);
    Seat seat = holdSeat(notExpiredYet, BOOKING_NUMBER);

    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));
    given(
            seatRepository.releaseExpiredHoldById(
                eq(seatId), eq(notExpiredYet), eq(SeatStatus.HOLD), eq(SeatStatus.AVAILABLE)))
        .willReturn(1);

    // when
    seatReleaseHoldUseCase.execute(BOOKING_NUMBER, seatId);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verify(seatStatusEventPublisher).publishAfterCommit(seat, SeatEventSource.CANCEL_RELEASE);
    // 트랜잭션 동기화가 없는 단위 테스트에서는 락 해제가 즉시 실행된다.
    verify(seatUnlockUseCase).forceRelease(seatId);
  }

  @Test
  @DisplayName("성공: 조회 스냅샷의 holdExpiredAt을 그대로 가드로 넘긴다")
  void execute_PassesSnapshotHoldExpiredAtAsGuard() {
    // given: DB에서 읽은 값을 그대로 넘겨야 한다. now()를 새로 만들면 나노초/마이크로초 차이로
    // 동등 비교가 빗나가 해제가 조용히 스킵된다(SeatRepository 주석).
    Long seatId = 1L;
    LocalDateTime snapshot = LocalDateTime.of(2026, 8, 2, 10, 35, 0, 123_456_000);
    Seat seat = holdSeat(snapshot, BOOKING_NUMBER);

    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));
    given(
            seatRepository.releaseExpiredHoldById(
                eq(seatId), eq(snapshot), eq(SeatStatus.HOLD), eq(SeatStatus.AVAILABLE)))
        .willReturn(1);

    // when
    seatReleaseHoldUseCase.execute(BOOKING_NUMBER, seatId);

    // then
    verify(seatRepository)
        .releaseExpiredHoldById(seatId, snapshot, SeatStatus.HOLD, SeatStatus.AVAILABLE);
  }

  @Test
  @DisplayName("스킵: 좌석이 HOLD가 아니면 반납하지 않는다")
  void execute_WhenSeatNotHold_Skips() {
    // given
    Long seatId = 1L;
    Seat seat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.SOLD)
            .build();
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when
    seatReleaseHoldUseCase.execute(BOOKING_NUMBER, seatId);

    // then
    verify(seatRepository, never())
        .releaseExpiredHoldById(anyLong(), any(LocalDateTime.class), any(), any());
    verifyNoInteractions(seatStatusEventPublisher, seatUnlockUseCase);
  }

  @Test
  @DisplayName("스킵: 다른 예매가 쥔 좌석은 반납하지 않는다")
  void execute_WhenBookingNumberMismatch_Skips() {
    // given: 취소한 예매와 좌석을 쥔 예매가 다르다. 조건부 UPDATE 가드는 소유자를 묻지 않으므로
    // 여기서 막지 않으면 남의 선점을 풀어버린다.
    Long seatId = 1L;
    Seat seat = holdSeat(LocalDateTime.now().plusMinutes(4), "OTHER-BOOKING");
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when
    seatReleaseHoldUseCase.execute(BOOKING_NUMBER, seatId);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.HOLD);
    verify(seatRepository, never())
        .releaseExpiredHoldById(anyLong(), any(LocalDateTime.class), any(), any());
    verifyNoInteractions(seatStatusEventPublisher, seatUnlockUseCase);
  }

  @Test
  @DisplayName("스킵: 조회 이후 선점 상태가 바뀌어 조건부 UPDATE가 0건이면 후속 처리를 하지 않는다")
  void execute_WhenConditionalUpdateAffectsNothing_Skips() {
    // given
    Long seatId = 1L;
    LocalDateTime snapshot = LocalDateTime.now().plusMinutes(4);
    Seat seat = holdSeat(snapshot, BOOKING_NUMBER);

    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));
    given(
            seatRepository.releaseExpiredHoldById(
                eq(seatId), eq(snapshot), eq(SeatStatus.HOLD), eq(SeatStatus.AVAILABLE)))
        .willReturn(0);

    // when
    seatReleaseHoldUseCase.execute(BOOKING_NUMBER, seatId);

    // then: 락도 풀지 않는다. 락을 지우면 TTL 만료 이벤트가 영영 오지 않아 회수 경로가 사라진다.
    verifyNoInteractions(seatStatusEventPublisher, seatUnlockUseCase);
  }

  @Test
  @DisplayName("스킵: 좌석이 존재하지 않으면 예외 없이 끝낸다")
  void execute_WhenSeatNotFound_Skips() {
    // given: 호출자(booking)는 예매 취소를 이미 확정했으므로 여기서 예외를 던져 되돌릴 것이 없다.
    Long seatId = 999L;
    given(seatRepository.findById(seatId)).willReturn(Optional.empty());

    // when
    seatReleaseHoldUseCase.execute(BOOKING_NUMBER, seatId);

    // then
    verifyNoInteractions(seatStatusEventPublisher, seatUnlockUseCase);
  }
}
