package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatMapItemResponse;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatGetSeatMapUseCaseTest {

  @InjectMocks private SeatGetSeatMapUseCase useCase;

  @Mock private SeatRepository seatRepository;

  @Test
  @DisplayName("공연 ID에 해당하는 정적 좌석 맵 리스트를 반환한다")
  void executeReturnsSeatLayouts() {
    // given
    Long performanceId = 1L;
    List<SeatMapItemResponse> expectedResponses =
        List.of(
            new SeatMapItemResponse(1L, 101L, "A-1", SeatStatus.AVAILABLE, null),
            new SeatMapItemResponse(2L, 101L, "A-2", SeatStatus.HOLD, null));
    given(seatRepository.findSeatMapByPerformanceId(performanceId)).willReturn(expectedResponses);

    // when
    List<SeatMapItemResponse> actualResponses = useCase.execute(performanceId);

    // then
    assertThat(actualResponses).hasSize(2);
    assertThat(actualResponses.getFirst().seatId()).isEqualTo(1L);
    assertThat(actualResponses.getFirst().seatNumber()).isEqualTo("A-1");
    assertThat(actualResponses.getFirst().seatStatus()).isEqualTo(SeatStatus.AVAILABLE);
  }
}
