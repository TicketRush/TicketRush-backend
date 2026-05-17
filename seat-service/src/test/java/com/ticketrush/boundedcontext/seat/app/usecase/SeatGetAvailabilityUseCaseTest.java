package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatAvailabilityResponse;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatGetAvailabilityUseCaseTest {

  @InjectMocks private SeatGetAvailabilityUseCase useCase;

  @Mock private SeatRepository seatRepository;

  @Test
  @DisplayName("공연 ID에 해당하는 잔여 좌석 수와 전체 좌석 수를 반환한다")
  void executeReturnsSeatAvailability() {
    // given
    Long performanceId = 1L;
    given(seatRepository.getAvailabilityByPerformanceId(performanceId, SeatStatus.AVAILABLE))
        .willReturn(new SeatAvailabilityResponse(8L, 10L));

    // when
    SeatAvailabilityResponse response = useCase.execute(performanceId);

    // then
    assertThat(response.availableCount()).isEqualTo(8L);
    assertThat(response.totalCount()).isEqualTo(10L);
    verify(seatRepository).getAvailabilityByPerformanceId(performanceId, SeatStatus.AVAILABLE);
  }

  @Test
  @DisplayName("잔여 좌석이 없으면 availableCount 0과 전체 좌석 수를 반환한다")
  void executeReturnsZeroAvailableCount() {
    // given
    Long performanceId = 1L;
    given(seatRepository.getAvailabilityByPerformanceId(performanceId, SeatStatus.AVAILABLE))
        .willReturn(new SeatAvailabilityResponse(0L, 10L));

    // when
    SeatAvailabilityResponse response = useCase.execute(performanceId);

    // then
    assertThat(response.availableCount()).isZero();
    assertThat(response.totalCount()).isEqualTo(10L);
  }
}
