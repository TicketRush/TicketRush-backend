package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatNumberResponse;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatGetNumbersUseCaseTest {

  @InjectMocks private SeatGetNumbersUseCase seatGetNumbersUseCase;

  @Mock private SeatRepository seatRepository;

  @Test
  @DisplayName("성공: 좌석 ID 목록 순서대로 좌석 번호를 반환한다")
  void execute_returns_in_requested_order() {
    // given
    List<Long> seatIds = List.of(2L, 1L);
    given(seatRepository.findSeatNumbersByIdIn(seatIds))
        .willReturn(List.of(new SeatNumberResponse(1L, "A-1"), new SeatNumberResponse(2L, "A-2")));

    // when
    List<SeatNumberResponse> result = seatGetNumbersUseCase.execute(seatIds);

    // then
    assertThat(result)
        .containsExactly(new SeatNumberResponse(2L, "A-2"), new SeatNumberResponse(1L, "A-1"));
    verify(seatRepository).findSeatNumbersByIdIn(seatIds);
  }

  @Test
  @DisplayName("성공: 빈 좌석 ID 목록이면 빈 목록을 반환한다")
  void execute_returns_empty_when_seat_ids_empty() {
    // when
    List<SeatNumberResponse> result = seatGetNumbersUseCase.execute(List.of());

    // then
    assertThat(result).isEmpty();
  }
}
