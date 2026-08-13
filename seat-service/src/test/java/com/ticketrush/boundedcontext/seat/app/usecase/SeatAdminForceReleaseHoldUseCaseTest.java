package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
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
    seatAdminForceReleaseHoldUseCase.execute(ADMIN_ID, PERFORMANCE_ID, SEAT_ID, BOOKING_NUMBER);

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
    seatAdminForceReleaseHoldUseCase.execute(ADMIN_ID, PERFORMANCE_ID, SEAT_ID, BOOKING_NUMBER);

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
    seatAdminForceReleaseHoldUseCase.execute(ADMIN_ID, PERFORMANCE_ID, SEAT_ID, BOOKING_NUMBER);

    // then
    verify(seatRepository)
        .releaseExpiredHoldById(SEAT_ID, snapshot, SeatStatus.HOLD, SeatStatus.AVAILABLE);
  }

  @Test
  @DisplayName("거절: 판매 완료 좌석은 환불로 안내하는 전용 코드로 거절한다")
  void execute_rejects_sold_seat() {
    // given: 결제된 좌석을 되돌리는 것은 환불이지 강제 해제가 아니다(#561 환불 API 담당).
    // 이미 해제된 좌석과 같은 코드로 뭉개면 화면이 '환불로 가라'를 안내하지 못한다.
    Seat seat = seat(SeatStatus.SOLD, null, BOOKING_NUMBER);
    given(seatRepository.findByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(Optional.of(seat));

    // when & then
    assertThatThrownBy(
            () ->
                seatAdminForceReleaseHoldUseCase.execute(
                    ADMIN_ID, PERFORMANCE_ID, SEAT_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.SEAT_SOLD_NOT_RELEASABLE);

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
            () ->
                seatAdminForceReleaseHoldUseCase.execute(
                    ADMIN_ID, PERFORMANCE_ID, SEAT_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.SEAT_NOT_HELD);

    verifyNoInteractions(seatHoldExpiredPublisher, seatStatusEventPublisher, seatUnlockUseCase);
  }

  @Test
  @DisplayName("거절: 관리자가 조회한 예매와 다른 예매가 좌석을 쥐고 있으면 해제하지 않는다")
  void execute_rejects_when_another_booking_took_over_the_seat() {
    /*
     * 이 이슈의 핵심 실패 모드다. 관리자 화면은 수동 갱신이라 조회와 클릭 사이가 길 수 있는데,
     * 그 사이 원래 선점이 만료되고 다른 사용자가 같은 좌석을 다시 잡을 수 있다. 이 가드가 없으면
     * 관리자는 BK-A를 지우려고 눌렀는데 아무 잘못 없는 BK-B의 결제 진행 중 예매가 EXPIRED로 끝난다.
     */
    LocalDateTime notExpiredYet = LocalDateTime.now().plusMinutes(4);
    Seat seat = seat(SeatStatus.HOLD, notExpiredYet, "BK-B-재선점");
    given(seatRepository.findByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(Optional.of(seat));

    // when & then: 관리자가 화면에서 본 것은 BOOKING_NUMBER(=BK-A)다
    assertThatThrownBy(
            () ->
                seatAdminForceReleaseHoldUseCase.execute(
                    ADMIN_ID, PERFORMANCE_ID, SEAT_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.SEAT_RELEASE_CONFLICT);

    // 좌석은 그대로 HOLD이고 어떤 이벤트도 나가지 않는다
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.HOLD);
    verify(seatRepository, never())
        .releaseExpiredHoldById(anyLong(), any(LocalDateTime.class), any(), any());
    verifyNoInteractions(seatHoldExpiredPublisher, seatStatusEventPublisher, seatUnlockUseCase);
  }

  @Test
  @DisplayName("성공: 예매 번호가 없는 HOLD 좌석은 전제조건 검사를 건너뛰고 해제한다")
  void execute_releases_ownerless_hold_without_precondition_check() {
    /*
     * 가드의 목적은 관리자가 모르는 제3의 예매를 종결시키지 않는 것인데, 좌석이 예매를 가리키지
     * 않으면 종결될 예매 자체가 없다. #95 이전에 만들어진 그런 좌석은 오히려 관리자가 풀어 줘야 할
     * 대상이라, 여기서 막으면 유일한 탈출구가 사라진다.
     */
    LocalDateTime notExpiredYet = LocalDateTime.now().plusMinutes(4);
    Seat seat = seat(SeatStatus.HOLD, notExpiredYet, null);

    given(seatRepository.findByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(Optional.of(seat));
    given(
            seatRepository.releaseExpiredHoldById(
                eq(SEAT_ID), eq(notExpiredYet), eq(SeatStatus.HOLD), eq(SeatStatus.AVAILABLE)))
        .willReturn(1);

    // when: 어떤 예매 번호를 보내도 통과한다 — 대조할 소유자가 없다
    seatAdminForceReleaseHoldUseCase.execute(ADMIN_ID, PERFORMANCE_ID, SEAT_ID, BOOKING_NUMBER);

    // then
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verify(seatUnlockUseCase).forceRelease(SEAT_ID);
  }

  @Test
  @DisplayName("성공: Redis 락 삭제가 실패해도 예외를 밖으로 내지 않는다 — 감사 로그가 뒤에서 끊기지 않게")
  void execute_swallows_redis_failure_after_commit() {
    // given: afterCommit 콜백은 개별 try/catch로 감싸이지 않아, 하나가 던지면 뒤 콜백이 실행되지 않는다.
    // 커밋은 이미 끝나 좌석은 AVAILABLE인데 관리자에게 500이 나가는 것도 잘못된 신호다.
    LocalDateTime notExpiredYet = LocalDateTime.now().plusMinutes(4);
    Seat seat = seat(SeatStatus.HOLD, notExpiredYet, BOOKING_NUMBER);

    given(seatRepository.findByIdAndPerformanceId(SEAT_ID, PERFORMANCE_ID))
        .willReturn(Optional.of(seat));
    given(
            seatRepository.releaseExpiredHoldById(
                eq(SEAT_ID), eq(notExpiredYet), eq(SeatStatus.HOLD), eq(SeatStatus.AVAILABLE)))
        .willReturn(1);
    willThrow(new IllegalStateException("redis down"))
        .given(seatUnlockUseCase)
        .forceRelease(SEAT_ID);

    // when & then: 던지지 않는다
    seatAdminForceReleaseHoldUseCase.execute(ADMIN_ID, PERFORMANCE_ID, SEAT_ID, BOOKING_NUMBER);

    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    verify(seatUnlockUseCase).forceRelease(SEAT_ID);
    verify(seatHoldExpiredPublisher).publish(seat);
  }

  @Test
  @DisplayName("거절: 조회 이후 선점 상태가 바뀌어 조건부 UPDATE가 0건이면 재시도를 안내하는 전용 코드로 거절한다")
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
            () ->
                seatAdminForceReleaseHoldUseCase.execute(
                    ADMIN_ID, PERFORMANCE_ID, SEAT_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.SEAT_RELEASE_CONFLICT);

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
            () ->
                seatAdminForceReleaseHoldUseCase.execute(
                    ADMIN_ID, PERFORMANCE_ID, SEAT_ID, BOOKING_NUMBER))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorStatus", ErrorStatus.SEAT_NOT_FOUND);

    verifyNoInteractions(seatHoldExpiredPublisher, seatStatusEventPublisher, seatUnlockUseCase);
  }
}
