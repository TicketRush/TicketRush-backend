package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsResponse;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatGetStatusCountsUseCaseTest {

  @InjectMocks private SeatGetStatusCountsUseCase useCase;

  @Mock private SeatRepository seatRepository;

  @Test
  @DisplayName("공연 ID에 해당하는 전체 좌석 수와 상태별 좌석 수를 반환한다")
  void executeReturnsSeatStatusCounts() {
    // given
    Long performanceId = 1L;
    given(
            seatRepository.getStatusCountsByPerformanceId(
                eq(performanceId), any(LocalDateTime.class)))
        .willReturn(new SeatStatusCountsResponse(10L, 6L, 3L, 1L));

    // when
    SeatStatusCountsResponse response = useCase.execute(performanceId);

    // then
    assertThat(response.totalCount()).isEqualTo(10L);
    assertThat(response.availableCount()).isEqualTo(6L);
    assertThat(response.soldCount()).isEqualTo(3L);
    assertThat(response.holdCount()).isEqualTo(1L);
    verify(seatRepository)
        .getStatusCountsByPerformanceId(eq(performanceId), any(LocalDateTime.class));
  }

  @Test
  @DisplayName("좌석이 없으면 모든 카운트를 0으로 반환한다")
  void executeReturnsZeroCounts() {
    // given
    Long performanceId = 1L;
    given(
            seatRepository.getStatusCountsByPerformanceId(
                eq(performanceId), any(LocalDateTime.class)))
        .willReturn(new SeatStatusCountsResponse(0L, 0L, 0L, 0L));

    // when
    SeatStatusCountsResponse response = useCase.execute(performanceId);

    // then
    assertThat(response.totalCount()).isZero();
    assertThat(response.availableCount()).isZero();
    assertThat(response.soldCount()).isZero();
    assertThat(response.holdCount()).isZero();
  }
}
