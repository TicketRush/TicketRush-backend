package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventPublisher;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatReleaseExpiredUseCaseTest {

  @Mock private SeatRepository seatRepository;
  @Mock private SeatStatusEventPublisher seatStatusEventPublisher;

  @InjectMocks private SeatReleaseExpiredUseCase seatReleaseExpiredUseCase;

  @Test
  @DisplayName("만료된 좌석을 조회하여 상태를 롤백하는 레포지토리 메서드를 호출한다")
  void execute_ReleasesExpiredSeats() {
    // given
    Seat expiredSeat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(1L)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(LocalDateTime.now().minusMinutes(1))
            .build();
    given(seatRepository.findExpiredHoldSeats(eq(SeatStatus.HOLD), any(LocalDateTime.class)))
        .willReturn(List.of(expiredSeat));

    // when
    seatReleaseExpiredUseCase.execute();

    // then
    verify(seatRepository).findExpiredHoldSeats(eq(SeatStatus.HOLD), any(LocalDateTime.class));
    verify(seatStatusEventPublisher).publishAfterCommit(expiredSeat);
    assertThat(expiredSeat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
  }
}
