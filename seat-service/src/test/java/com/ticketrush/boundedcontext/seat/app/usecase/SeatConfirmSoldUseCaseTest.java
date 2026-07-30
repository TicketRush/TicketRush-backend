package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ticketrush.boundedcontext.seat.app.support.SeatEventSource;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.types.SeatStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatConfirmSoldUseCaseTest {

  @InjectMocks private SeatConfirmSoldUseCase seatConfirmSoldUseCase;

  @Mock private SeatRepository seatRepository;
  @Mock private SeatUnlockUseCase seatUnlockUseCase;
  @Mock private SeatStatusEventPublisher seatStatusEventPublisher;

  @Test
  @DisplayName("성공: HOLD 상태인 좌석을 SOLD 상태로 확정한다")
  void execute_success() {
    // given
    String bookingNumber = "X7B29-KLPW1";
    Long seatId = 1L;
    Seat seat =
        Seat.builder().performanceId(1L).seatNumber("A-1").seatStatus(SeatStatus.SOLD).build();

    given(seatRepository.confirmSoldById(seatId, bookingNumber, SeatStatus.HOLD, SeatStatus.SOLD))
        .willReturn(1);
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when
    seatConfirmSoldUseCase.execute(bookingNumber, seatId);

    // then
    verify(seatRepository).confirmSoldById(seatId, bookingNumber, SeatStatus.HOLD, SeatStatus.SOLD);
    verify(seatRepository).findById(seatId);
    verify(seatStatusEventPublisher).publishAfterCommit(seat, SeatEventSource.CONFIRM_SOLD);
    verify(seatUnlockUseCase).forceRelease(seatId);
  }

  @Test
  @DisplayName("실패: 존재하지 않는 좌석이면 BusinessException(SEAT_NOT_FOUND)이 발생한다")
  void execute_fail_seat_not_found() {
    // given
    Long seatId = 1L;
    String bookingNumber = "X7B29-KLPW1";

    given(seatRepository.confirmSoldById(seatId, bookingNumber, SeatStatus.HOLD, SeatStatus.SOLD))
        .willReturn(0);
    given(seatRepository.findById(seatId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> seatConfirmSoldUseCase.execute(bookingNumber, seatId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.SEAT_NOT_FOUND);

    verify(seatRepository).confirmSoldById(seatId, bookingNumber, SeatStatus.HOLD, SeatStatus.SOLD);
    verify(seatRepository).findById(seatId);
    verifyNoMoreInteractions(seatRepository, seatUnlockUseCase, seatStatusEventPublisher);
  }

  @Test
  @DisplayName("성공: 이미 같은 예매로 SOLD된 좌석이면 멱등 성공으로 정상 반환한다")
  void execute_success_idempotent_already_sold_to_same_booking() {
    // given — 결제 완료 이벤트가 재수신돼 확정이 두 번 호출된 상황. 목표 상태에 이미 도달했다.
    Long seatId = 1L;
    String bookingNumber = "X7B29-KLPW1";
    Seat seat =
        Seat.builder()
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.SOLD)
            .bookingNumber(bookingNumber)
            .build();

    given(seatRepository.confirmSoldById(seatId, bookingNumber, SeatStatus.HOLD, SeatStatus.SOLD))
        .willReturn(0);
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when & then — 예외 없이 반환한다(HTTP 200).
    assertThatCode(() -> seatConfirmSoldUseCase.execute(bookingNumber, seatId))
        .doesNotThrowAnyException();

    // 부수효과는 다시 실행하지 않는다. 특히 forceRelease를 부르면 트랜잭션 안 동기 Redis 호출이 되어
    // Redis 장애 시 정상 중복이 503이 되고, 호출자가 재시도→DLT로 빠진다.
    verifyNoInteractions(seatUnlockUseCase, seatStatusEventPublisher);
  }

  @Test
  @DisplayName("실패: 만료 해제되어 AVAILABLE이 된 좌석이면 BusinessException(SEAT_CONFIRM_NOT_OWNED)이 발생한다")
  void execute_fail_seat_released_by_expiration() {
    // given — 대량 만료 창에서 fallback 스케줄러가 이미 좌석을 해제했다(#489). 과금 후 좌석 없음.
    Long seatId = 1L;
    String bookingNumber = "X7B29-KLPW1";
    Seat seat =
        Seat.builder()
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.AVAILABLE)
            .bookingNumber(null)
            .build();

    given(seatRepository.confirmSoldById(seatId, bookingNumber, SeatStatus.HOLD, SeatStatus.SOLD))
        .willReturn(0);
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when & then
    assertThatThrownBy(() -> seatConfirmSoldUseCase.execute(bookingNumber, seatId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.SEAT_CONFIRM_NOT_OWNED);

    verifyNoInteractions(seatUnlockUseCase, seatStatusEventPublisher);
  }

  @Test
  @DisplayName("실패: 다른 예매가 SOLD로 점유한 좌석이면 BusinessException(SEAT_CONFIRM_NOT_OWNED)이 발생한다")
  void execute_fail_seat_sold_to_another_booking() {
    // given — 해제된 좌석을 다른 사용자가 가져가 확정까지 마쳤다. 정상 중복(멱등)과 반드시 갈려야 한다.
    Long seatId = 1L;
    String bookingNumber = "X7B29-KLPW1";
    Seat seat =
        Seat.builder()
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.SOLD)
            .bookingNumber("OTHER-BOOKING")
            .build();

    given(seatRepository.confirmSoldById(seatId, bookingNumber, SeatStatus.HOLD, SeatStatus.SOLD))
        .willReturn(0);
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when & then
    assertThatThrownBy(() -> seatConfirmSoldUseCase.execute(bookingNumber, seatId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.SEAT_CONFIRM_NOT_OWNED);

    verifyNoInteractions(seatUnlockUseCase, seatStatusEventPublisher);
  }

  @Test
  @DisplayName("실패: 다른 예매가 HOLD 중인 좌석이면 BusinessException(SEAT_CONFIRM_NOT_OWNED)이 발생한다")
  void execute_fail_seat_held_by_another_booking() {
    // given
    Long seatId = 1L;
    String bookingNumber = "X7B29-KLPW1";
    Seat seat =
        Seat.builder()
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.HOLD)
            .bookingNumber("OTHER-BOOKING")
            .build();

    given(seatRepository.confirmSoldById(seatId, bookingNumber, SeatStatus.HOLD, SeatStatus.SOLD))
        .willReturn(0);
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when & then
    assertThatThrownBy(() -> seatConfirmSoldUseCase.execute(bookingNumber, seatId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.SEAT_CONFIRM_NOT_OWNED);

    verifyNoInteractions(seatUnlockUseCase, seatStatusEventPublisher);
  }
}
