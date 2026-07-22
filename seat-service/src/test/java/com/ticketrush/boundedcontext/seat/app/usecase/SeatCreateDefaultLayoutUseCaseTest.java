package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutResponse;
import com.ticketrush.boundedcontext.seat.domain.entity.SeatLayout;
import com.ticketrush.boundedcontext.seat.out.repository.SeatLayoutRepository;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@Import(SeatCreateDefaultLayoutUseCase.class)
class SeatCreateDefaultLayoutUseCaseTest {

  @Autowired private SeatCreateDefaultLayoutUseCase useCase;

  @Autowired private SeatRepository seatRepository;

  @Autowired private SeatLayoutRepository seatLayoutRepository;

  @Test
  @DisplayName("공연 ID 기준 기본 좌석 배치도와 AVAILABLE 좌석 120개를 생성한다")
  void executeCreatesDefaultLayoutAndSeats() {
    // given
    Long performanceId = 1L;

    // when
    useCase.execute(performanceId);

    // then
    List<SeatLayout> layouts = seatLayoutRepository.findAll();
    assertThat(layouts).hasSize(1);
    assertThat(layouts.getFirst().getPerformanceId()).isEqualTo(performanceId);
    assertThat(layouts.getFirst().getTotalRows()).isEqualTo(10);
    assertThat(layouts.getFirst().getMaxCols()).isEqualTo(12);

    List<SeatLayoutResponse> seats = seatRepository.findSeatLayoutsByPerformanceId(performanceId);
    assertThat(seats).hasSize(120);
    assertThat(seats).extracting(SeatLayoutResponse::seatStatus).containsOnly(SeatStatus.AVAILABLE);
    assertThat(seats).extracting(SeatLayoutResponse::seatNumber).contains("A-1", "J-12");
  }

  @Test
  @DisplayName("같은 공연 ID로 중복 실행해도 좌석 배치도와 좌석이 중복 생성되지 않는다")
  void executeIsIdempotentByPerformanceId() {
    // given
    Long performanceId = 1L;

    // when
    useCase.execute(performanceId);
    useCase.execute(performanceId);

    // then
    assertThat(seatLayoutRepository.findAll()).hasSize(1);
    assertThat(seatRepository.findSeatLayoutsByPerformanceId(performanceId)).hasSize(120);
  }

  @Test
  @DisplayName("공연 ID에 대한 좌석 배치도는 DB 유니크 제약으로 중복 생성을 막는다")
  void performanceIdIsUnique() {
    // given
    Long performanceId = 1L;
    SeatLayout firstLayout =
        SeatLayout.builder().performanceId(performanceId).totalRows(10).maxCols(12).build();
    SeatLayout duplicatedLayout =
        SeatLayout.builder().performanceId(performanceId).totalRows(10).maxCols(12).build();

    seatLayoutRepository.saveAndFlush(firstLayout);

    // when & then
    assertThatThrownBy(() -> seatLayoutRepository.saveAndFlush(duplicatedLayout))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
