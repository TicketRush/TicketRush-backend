package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatReleaseSingleUseCaseTest {

  @Mock private SeatRepository seatRepository;
  @Mock private SeatStatusEventPublisher seatStatusEventPublisher;

  @InjectMocks private SeatReleaseSingleUseCase seatReleaseSingleUseCase;

  @Test
  @DisplayName("존재하는 좌석의 만료 이벤트 수신 시 상태를 AVAILABLE로 롤백한다")
  void execute_WhenSeatExists_ReleasesHold() {
    // given
    Long seatId = 1L;
    Seat seat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.HOLD)
            .build();
    given(seatRepository.findById(seatId)).willReturn(Optional.of(seat));

    // when
    seatReleaseSingleUseCase.execute(seatId);

    // then
    verify(seatRepository).findById(seatId);
    verify(seatStatusEventPublisher).publishAfterCommit(seat);
  }

  @Test
  @DisplayName("HOLD 상태가 아닌 좌석의 만료 이벤트 수신 시 상태 변경 이벤트를 발행하지 않는다")
  void execute_WhenSeatIsNotHold_DoesNotPublishEvent() {
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
    seatReleaseSingleUseCase.execute(seatId);

    // then
    verify(seatRepository).findById(seatId);
    verifyNoInteractions(seatStatusEventPublisher);
  }

  @Test
  @DisplayName("존재하지 않는 좌석의 만료 이벤트 수신 시 예외를 던지지 않고 경고 로그만 남긴다")
  void execute_WhenSeatDoesNotExist_DoesNotThrow() {
    // given
    Long seatId = 999L;
    given(seatRepository.findById(seatId)).willReturn(Optional.empty());

    // when
    seatReleaseSingleUseCase.execute(seatId);

    // then
    verify(seatRepository).findById(seatId);
    verify(mock(Seat.class), never()).releaseHold();
  }
}
