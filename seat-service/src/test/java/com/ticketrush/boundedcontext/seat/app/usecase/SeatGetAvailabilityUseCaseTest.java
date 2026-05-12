package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

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
    given(seatRepository.countByPerformanceIdAndSeatStatus(performanceId, SeatStatus.AVAILABLE))
        .willReturn(8L);
    given(seatRepository.countByPerformanceId(performanceId)).willReturn(10L);

    // when
    SeatAvailabilityResponse response = useCase.execute(performanceId);

    // then
    assertThat(response.availableCount()).isEqualTo(8L);
    assertThat(response.totalCount()).isEqualTo(10L);
  }
}
