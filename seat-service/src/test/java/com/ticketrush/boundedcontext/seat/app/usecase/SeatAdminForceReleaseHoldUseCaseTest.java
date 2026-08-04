package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.seat.app.support.SeatEventSource;
import com.ticketrush.boundedcontext.seat.app.support.SeatHoldExpiredPublisher;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatAdminForceReleaseHoldUseCaseTest {

  private static final String BOOKING_NUMBER = "X7B29-KLPW1";
  private static final Long ADMIN_ID = 42L;
  private static final Long PERFORMANCE_ID = 1L;
  private static final Long SEAT_ID = 100L;

  @Mock private SeatRepository seatRepository;
  @Mock private SeatStatusEventPublisher seatStatusEventPublisher;
  @Mock private SeatHoldExpiredPublisher seatHoldExpiredPublisher;
  @Mock private SeatUnlockUseCase seatUnlockUseCase;

  @InjectMocks private SeatAdminForceReleaseHoldUseCase seatAdminForceReleaseHoldUseCase;

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
  @DisplayName("성공: 아직 만료되지 않은 HOLD를 AVAILABLE로 강제 해제한다")
  void execute_releases_hold_before_expiry() {
    // given: 만료까지 4분 남은 선점. 강제 해제는 정의상 '만료 전' 좌석을 되돌리는 것이므로
    // 조건부 UPDATE 가드에 now() 비교가 없어야 이 케이스가 통과한다.
    LocalDateTime notExpiredYet = LocalDateTime.now().plusMinutes(4);
    Seat seat = seat(SeatStatus.HOLD, notExpiredYet, BOOKING_NUMBER);

    given(seatRepository.findByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(Optional.of(seat));
    given(
            seatRepository.releaseExpiredHoldById(
                eq(SEAT_ID), eq(notExpiredYet), eq(SeatStatus.HOLD), eq(SeatStatus.AVAILABLE)))
        .willReturn(1);

    // when
    seatAdminForceReleaseHoldUseCase.execute(ADMIN_ID, PERFORMANCE_ID, SEAT_ID);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verify(seatStatusEventPublisher).publishAfterCommit(seat, SeatEventSource.ADMIN_FORCE_RELEASE);
    // 트랜잭션 동기화가 없는 단위 테스트에서는 락 해제가 즉시 실행된다.
    // 강제 해제는 만료 전이라 Redis 락 키가 살아 있어 반드시 지워야 한다 — 안 지우면 DB는 AVAILABLE인데
    // 잔여 TTL 동안 재선점이 막힌다.
    verify(seatUnlockUseCase).forceRelease(SEAT_ID);
  }

  @Test
  @DisplayName("성공: 예매를 만료시킬 SeatHoldExpiredEvent를 releaseHold() 이전에 발행한다")
  void execute_publishes_hold_expired_event_before_clearing_booking_number() {
    // given: 예매 정합(PENDING → EXPIRED)의 전부가 이 이벤트다. releaseHold()가 bookingNumber를 null로
    // 지우므로 순서가 뒤집히면 발행이 조용히 스킵되고 예매가 PENDING에 남는다.
    LocalDateTime notExpiredYet = LocalDateTime.now().plusMinutes(4);
    Seat seat = seat(SeatStatus.HOLD, notExpiredYet, BOOKING_NUMBER);

    given(seatRepository.findByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(Optional.of(seat));
    given(
            seatRepository.releaseExpiredHoldById(
                eq(SEAT_ID), eq(notExpiredYet), eq(SeatStatus.HOLD), eq(SeatStatus.AVAILABLE)))
        .willReturn(1);

    // when
    seatAdminForceReleaseHoldUseCase.execute(ADMIN_ID, PERFORMANCE_ID, SEAT_ID);

    // then: publish가 먼저, 그다음 상태 변경 이벤트
    InOrder inOrder = inOrder(seatHoldExpiredPublisher, seatStatusEventPublisher);
    inOrder.verify(seatHoldExpiredPublisher).publish(seat);
    inOrder
        .verify(seatStatusEventPublisher)
        .publishAfterCommit(seat, SeatEventSource.ADMIN_FORCE_RELEASE);
  }

  @Test
  @DisplayName("성공: 조회 스냅샷의 holdExpiredAt을 그대로 가드로 넘긴다")
  void execute_passes_snapshot_hold_expired_at_as_guard() {
    // given: DB에서 읽은 값을 그대로 넘겨야 한다. now()를 새로 만들면 나노초/마이크로초 차이로
    // 동등 비교가 빗나가 해제가 조용히 스킵된다(SeatRepository 주석).
    LocalDateTime snapshot = LocalDateTime.of(2026, 8, 2, 10, 35, 0, 123_456_000);
    Seat seat = seat(SeatStatus.HOLD, snapshot, BOOKING_NUMBER);

    given(seatRepository.findByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(Optional.of(seat));
    given(
            seatRepository.releaseExpiredHoldById(
                eq(SEAT_ID), eq(snapshot), eq(SeatStatus.HOLD), eq(SeatStatus.AVAILABLE)))
        .willReturn(1);

    // when
    seatAdminForceReleaseHoldUseCase.execute(ADMIN_ID, PERFORMANCE_ID, SEAT_ID);

    // then
    verify(seatRepository)
        .releaseExpiredHoldById(SEAT_ID, snapshot, SeatStatus.HOLD, SeatStatus.AVAILABLE);
  }

  @Test
  @DisplayName("거절: 판매 완료 좌석은 409로 거절하고 아무것도 건드리지 않는다")
  void execute_rejects_sold_seat() {
    // given: 결제된 좌석을 되돌리는 것은 환불이지 강제 해제가 아니다(#561 환불 API 담당).
    Seat seat = seat(SeatStatus.SOLD, null, BOOKING_NUMBER);
    given(seatRepository.findByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(Optional.of(seat));

    // when & then
    assertThatThrownBy(
            () -> seatAdminForceReleaseHoldUseCase.execute(ADMIN_ID, PERFORMANCE_ID, SEAT_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.SEAT_NOT_HELD);

    verify(seatRepository, never())
        .releaseExpiredHoldById(anyLong(), any(LocalDateTime.class), any(), any());
    verifyNoInteractions(seatHoldExpiredPublisher, seatStatusEventPublisher, seatUnlockUseCase);
  }

  @Test
  @DisplayName("거절: 이미 해제된 좌석은 409로 거절한다 — 관리자에게 조용한 성공을 주지 않는다")
  void execute_rejects_available_seat() {
    // given
    Seat seat = seat(SeatStatus.AVAILABLE, null, null);
    given(seatRepository.findByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(Optional.of(seat));

    // when & then
    assertThatThrownBy(
            () -> seatAdminForceReleaseHoldUseCase.execute(ADMIN_ID, PERFORMANCE_ID, SEAT_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.SEAT_NOT_HELD);

    verifyNoInteractions(seatHoldExpiredPublisher, seatStatusEventPublisher, seatUnlockUseCase);
  }

  @Test
  @DisplayName("거절: 조회 이후 선점 상태가 바뀌어 조건부 UPDATE가 0건이면 409로 거절한다")
  void execute_rejects_when_conditional_update_affects_nothing() {
    // given: 조회와 UPDATE 사이에 결제가 확정됐거나 다른 예매가 재선점했다.
    LocalDateTime snapshot = LocalDateTime.now().plusMinutes(4);
    Seat seat = seat(SeatStatus.HOLD, snapshot, BOOKING_NUMBER);

    given(seatRepository.findByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(Optional.of(seat));
    given(
            seatRepository.releaseExpiredHoldById(
                eq(SEAT_ID), eq(snapshot), eq(SeatStatus.HOLD), eq(SeatStatus.AVAILABLE)))
        .willReturn(0);

    // when & then
    assertThatThrownBy(
            () -> seatAdminForceReleaseHoldUseCase.execute(ADMIN_ID, PERFORMANCE_ID, SEAT_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.SEAT_NOT_HELD);

    // 이벤트도 락 해제도 없다. 락을 지우면 TTL 만료 이벤트가 영영 오지 않아 회수 경로가 사라진다.
    verifyNoInteractions(seatHoldExpiredPublisher, seatStatusEventPublisher, seatUnlockUseCase);
  }

  @Test
  @DisplayName("거절: 다른 공연의 좌석 ID는 404로 거절한다 — 경로의 공연 ID가 장식이 아니다")
  void execute_rejects_seat_of_other_performance() {
    // given
    given(seatRepository.findByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(
            () -> seatAdminForceReleaseHoldUseCase.execute(ADMIN_ID, PERFORMANCE_ID, SEAT_ID))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.SEAT_NOT_FOUND);

    verifyNoInteractions(seatHoldExpiredPublisher, seatStatusEventPublisher, seatUnlockUseCase);
  }
}
