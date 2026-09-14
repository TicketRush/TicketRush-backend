package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutSizeResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatMapItemResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatMapResponse;
import com.ticketrush.boundedcontext.seat.out.repository.SeatLayoutRepository;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatGetSeatMapUseCaseTest {

  private static final Long PERFORMANCE_ID = 1L;

  @InjectMocks private SeatGetSeatMapUseCase useCase;

  @Mock private SeatRepository seatRepository;
  @Mock private SeatLayoutRepository seatLayoutRepository;

  @Test
  @DisplayName("배치 크기와 좌표를 포함한 좌석 목록을 함께 반환한다")
  void executeReturnsLayoutAndSeats() {
    // given
    given(seatLayoutRepository.findSizeByPerformanceId(PERFORMANCE_ID))
        .willReturn(Optional.of(new SeatLayoutSizeResponse(10, 12)));
    given(seatRepository.findSeatMapByPerformanceId(PERFORMANCE_ID))
        .willReturn(
            List.of(
                new SeatMapItemResponse(1L, 101L, "A-1", 1, 1, SeatStatus.AVAILABLE, null),
                new SeatMapItemResponse(2L, 101L, "A-2", 1, 2, SeatStatus.HOLD, null)));

    // when
    SeatMapResponse response = useCase.execute(PERFORMANCE_ID);

    // then
    assertThat(response.layout()).isEqualTo(new SeatLayoutSizeResponse(10, 12));
    assertThat(response.seats()).hasSize(2);
    assertThat(response.seats().get(1).seatRow()).isEqualTo(1);
    assertThat(response.seats().get(1).seatCol()).isEqualTo(2);
  }

  @Test
  @DisplayName("배치도가 없으면 layout은 null, 좌석은 빈 목록이다")
  void executeReturnsNullLayoutWhenNotCreated() {
    // given
    given(seatLayoutRepository.findSizeByPerformanceId(PERFORMANCE_ID))
        .willReturn(Optional.empty());
    given(seatRepository.findSeatMapByPerformanceId(PERFORMANCE_ID)).willReturn(List.of());

    // when
    SeatMapResponse response = useCase.execute(PERFORMANCE_ID);

    // then
    assertThat(response.layout()).isNull();
    assertThat(response.seats()).isEmpty();
  }
}
