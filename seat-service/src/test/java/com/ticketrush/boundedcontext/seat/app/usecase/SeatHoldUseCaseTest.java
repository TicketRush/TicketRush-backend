package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

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
class SeatHoldUseCaseTest {

  @InjectMocks private SeatHoldUseCase seatHoldUseCase;

  @Mock private SeatRepository seatRepository;

  @Test
  @DisplayName("성공: 좌석이 존재하고 만료 시간이 유효하면 HOLD 상태로 변경된다")
  void execute_success() {
    // given
    Long seatId = 1L;
    LocalDateTime expiredAt = LocalDateTime.now().plusMinutes(5);

    // mock() 대신 실제 Seat 객체 생성 (초기 상태: AVAILABLE)
    Seat seat =
        Seat.builder()
            .seatLayoutId(10L)
            .performanceId(100L)
            .seatNumber("A-01")
            .seatStatus(SeatStatus.AVAILABLE)
            .build();

    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when
    seatHoldUseCase.execute(seatId, expiredAt);

    // then
    // verify(mock) 대신 실제 객체의 상태가 변했는지 직접 확인
    assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.HOLD);
    assertThat(seat.getHoldExpiredAt()).isEqualTo(expiredAt);
  }

  @Test
  @DisplayName("실패: 만료 시간이 과거일 경우 BusinessException(SEAT_HOLD_TIME_INVALID)이 발생한다")
  void execute_fail_invalid_expired_at() {
    // given
    Long seatId = 1L;
    LocalDateTime pastTime = LocalDateTime.now().minusMinutes(5); // 과거 시간 세팅

    Seat seat = Seat.builder().seatStatus(SeatStatus.AVAILABLE).build();

    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when & then
    assertThatThrownBy(() -> seatHoldUseCase.execute(seatId, pastTime))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.SEAT_HOLD_TIME_INVALID);
  }

  @Test
  @DisplayName("실패: 좌석이 존재하지 않으면 BusinessException(SEAT_NOT_FOUND)이 발생한다")
  void execute_fail_seat_not_found() {
    // given
    Long seatId = 1L;
    LocalDateTime expiredAt = LocalDateTime.now().plusMinutes(5);

    given(seatRepository.findById(seatId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> seatHoldUseCase.execute(seatId, expiredAt))
        .isInstanceOf(BusinessException.class)
        .extracting("errorStatus")
        .isEqualTo(ErrorStatus.SEAT_NOT_FOUND);
  }
}
