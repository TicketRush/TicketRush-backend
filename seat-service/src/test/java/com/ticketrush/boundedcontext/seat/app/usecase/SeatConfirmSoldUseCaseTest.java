package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.types.SeatStatus;
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

  @Test
  @DisplayName("성공: HOLD 상태인 좌석을 SOLD 상태로 확정한다")
  void execute_success() {
    // given
    String bookingNumber = "X7B29-KLPW1";
    Long seatId = 1L;

    given(seatRepository.confirmSoldById(seatId, SeatStatus.HOLD, SeatStatus.SOLD)).willReturn(1);

    // when
    seatConfirmSoldUseCase.execute(bookingNumber, seatId);

    // then
    verify(seatRepository).confirmSoldById(seatId, SeatStatus.HOLD, SeatStatus.SOLD);
    verify(seatUnlockUseCase).forceRelease(seatId);
  }

  @Test
  @DisplayName("실패: 존재하지 않는 좌석이면 BusinessException(SEAT_NOT_FOUND)이 발생한다")
  void execute_fail_seat_not_found() {
    // given
    Long seatId = 1L;

    given(seatRepository.confirmSoldById(seatId, SeatStatus.HOLD, SeatStatus.SOLD)).willReturn(0);
    given(seatRepository.existsById(seatId)).willReturn(false);

    // when & then
    assertThatThrownBy(() -> seatConfirmSoldUseCase.execute("X7B29-KLPW1", seatId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.SEAT_NOT_FOUND);

    verify(seatRepository).confirmSoldById(seatId, SeatStatus.HOLD, SeatStatus.SOLD);
    verify(seatRepository).existsById(seatId);
    verifyNoMoreInteractions(seatRepository, seatUnlockUseCase);
  }

  @Test
  @DisplayName("실패: HOLD 상태가 아닌 좌석이 있으면 BusinessException(SEAT_NOT_AVAILABLE)이 발생한다")
  void execute_fail_seat_not_hold() {
    // given
    Long seatId = 1L;

    given(seatRepository.confirmSoldById(seatId, SeatStatus.HOLD, SeatStatus.SOLD)).willReturn(0);
    given(seatRepository.existsById(seatId)).willReturn(true);

    // when & then
    assertThatThrownBy(() -> seatConfirmSoldUseCase.execute("X7B29-KLPW1", seatId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.SEAT_NOT_AVAILABLE);

    verify(seatRepository).confirmSoldById(seatId, SeatStatus.HOLD, SeatStatus.SOLD);
    verify(seatRepository).existsById(seatId);
    verifyNoMoreInteractions(seatRepository, seatUnlockUseCase);
  }
}
