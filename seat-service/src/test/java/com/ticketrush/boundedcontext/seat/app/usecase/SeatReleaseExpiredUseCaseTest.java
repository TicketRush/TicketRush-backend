package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatReleaseExpiredUseCaseTest {

  @Mock private SeatRepository seatRepository;

  @InjectMocks private SeatReleaseExpiredUseCase seatReleaseExpiredUseCase;

  @Test
  @DisplayName("만료된 좌석을 조회하여 상태를 롤백하는 레포지토리 메서드를 호출한다")
  void execute_ReleasesExpiredSeats() {
    // given
    given(seatRepository.releaseExpiredSeats(any(), any(), any())).willReturn(5);

    // when
    seatReleaseExpiredUseCase.execute();

    // then
    verify(seatRepository)
        .releaseExpiredSeats(
            eq(SeatStatus.AVAILABLE), eq(SeatStatus.HOLD), any(LocalDateTime.class));
  }
}
