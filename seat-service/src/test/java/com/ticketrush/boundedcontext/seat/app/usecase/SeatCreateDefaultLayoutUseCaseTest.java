package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatMapItemResponse;
import com.ticketrush.boundedcontext.seat.domain.entity.SeatLayout;
import com.ticketrush.boundedcontext.seat.out.repository.SeatLayoutRepository;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import com.ticketrush.shared.performance.event.PerformanceCreatedEvent;
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
  @DisplayName("좌석 수가 없으면 기본 좌석 배치도와 AVAILABLE 좌석 120개를 생성한다")
  void executeCreatesDefaultLayoutAndSeats() {
    // given
    Long performanceId = 1L;

    // when
    useCase.execute(performanceId, null);

    // then
    List<SeatLayout> layouts = seatLayoutRepository.findAll();
    assertThat(layouts).hasSize(1);
    assertThat(layouts.getFirst().getPerformanceId()).isEqualTo(performanceId);
    assertThat(layouts.getFirst().getTotalRows()).isEqualTo(10);
    assertThat(layouts.getFirst().getMaxCols()).isEqualTo(12);

    List<SeatMapItemResponse> seats = seatRepository.findSeatMapByPerformanceId(performanceId);
    assertThat(seats).hasSize(120);
    assertThat(seats)
        .extracting(SeatMapItemResponse::seatStatus)
        .containsOnly(SeatStatus.AVAILABLE);
    assertThat(seats).extracting(SeatMapItemResponse::seatNumber).contains("A-1", "J-12");
  }

  @Test
  @DisplayName("좌석 수 120이면 기존과 동일하게 10행 x 12열 120석을 생성한다 (하위호환)")
  void executeWith120CreatesSameLayoutAsBefore() {
    // given
    Long performanceId = 1L;

    // when
    useCase.execute(performanceId, 120);

    // then: #590 이전의 하드코딩 값과 정확히 같아야 한다. 기획의 기본 배치가 그대로 성립한다는 증명이다.
    SeatLayout layout = seatLayoutRepository.findAll().getFirst();
    assertThat(layout.getTotalRows()).isEqualTo(10);
    assertThat(layout.getMaxCols()).isEqualTo(12);

    List<SeatMapItemResponse> seats = seatRepository.findSeatMapByPerformanceId(performanceId);
    assertThat(seats).hasSize(120);
    assertThat(seats).extracting(SeatMapItemResponse::seatNumber).contains("A-1", "J-12");
  }

  @Test
  @DisplayName("좌석 수가 12열 x 26행을 넘으면 행을 26으로 묶고 열을 늘린다")
  void executeWidensColumnsBeyondRowLimit() {
    // given: 500석이면 12열 기준 42행이 필요한데, 행 이름이 'A'..'Z' 한 글자라 26행이 상한이다.
    Long performanceId = 1L;

    // when
    useCase.execute(performanceId, 500);

    // then: 12열로는 42행이 필요해 26행 상한에 걸리므로 20열로 넓힌다. 500석은 25행에서 정확히 끝나므로
    // 배치도에도 25행으로 남는다 — 좌석이 하나도 없는 26번째 행을 만들지 않는다.
    SeatLayout layout = seatLayoutRepository.findAll().getFirst();
    assertThat(layout.getTotalRows()).isEqualTo(25);
    assertThat(layout.getMaxCols()).isEqualTo(20);

    List<SeatMapItemResponse> seats = seatRepository.findSeatMapByPerformanceId(performanceId);
    assertThat(seats).hasSize(500);
    assertThat(seats).extracting(SeatMapItemResponse::seatNumber).contains("A-1", "Y-20");
    assertThat(seats).extracting(SeatMapItemResponse::seatNumber).doesNotContain("Z-1");
  }

  @Test
  @DisplayName("좌석 수가 그리드에 딱 맞지 않으면 마지막 행을 부분 행으로 만든다")
  void executeCreatesPartialLastRow() {
    // given: 125석이면 11행 x 12열 = 132칸이라 마지막 행이 5석에서 끊긴다.
    Long performanceId = 1L;

    // when
    useCase.execute(performanceId, 125);

    // then
    SeatLayout layout = seatLayoutRepository.findAll().getFirst();
    assertThat(layout.getTotalRows()).isEqualTo(11);
    assertThat(layout.getMaxCols()).isEqualTo(12);

    List<SeatMapItemResponse> seats = seatRepository.findSeatMapByPerformanceId(performanceId);
    assertThat(seats).hasSize(125);
    assertThat(seats).extracting(SeatMapItemResponse::seatNumber).contains("J-12", "K-5");
    assertThat(seats).extracting(SeatMapItemResponse::seatNumber).doesNotContain("K-6");
  }

  @Test
  @DisplayName("좌석 수가 상한을 넘으면 상한만큼만 생성하고 좌석 번호가 A-Z 범위를 벗어나지 않는다")
  void executeClampsToMaxTotalSeats() {
    // given
    Long performanceId = 1L;

    // when
    useCase.execute(performanceId, PerformanceCreatedEvent.MAX_TOTAL_SEATS + 1);

    // then
    List<SeatMapItemResponse> seats = seatRepository.findSeatMapByPerformanceId(performanceId);
    assertThat(seats).hasSize(PerformanceCreatedEvent.MAX_TOTAL_SEATS);

    // 좌석 번호는 varchar(10)이다. 행이 26개 상한이라 행 이름은 항상 한 글자고, 열은 최대 세 자리다.
    assertThat(seats)
        .extracting(SeatMapItemResponse::seatNumber)
        .allSatisfy(
            seatNumber -> {
              assertThat(seatNumber).matches("[A-Z]-\\d+");
              assertThat(seatNumber.length()).isLessThanOrEqualTo(10);
            });
  }

  @Test
  @DisplayName("좌석 수가 0 이하면 기본값 120석으로 생성한다")
  void executeFallsBackToDefaultWhenNotPositive() {
    // given
    Long performanceId = 1L;

    // when
    useCase.execute(performanceId, 0);

    // then
    assertThat(seatRepository.findSeatMapByPerformanceId(performanceId)).hasSize(120);
  }

  @Test
  @DisplayName("좌석 수가 1이면 좌석 한 개만 생성한다")
  void executeCreatesSingleSeat() {
    // given
    Long performanceId = 1L;

    // when
    useCase.execute(performanceId, 1);

    // then
    List<SeatMapItemResponse> seats = seatRepository.findSeatMapByPerformanceId(performanceId);
    assertThat(seats).hasSize(1);
    assertThat(seats.getFirst().seatNumber()).isEqualTo("A-1");
  }

  @Test
  @DisplayName("같은 공연 ID로 중복 실행해도 좌석 배치도와 좌석이 중복 생성되지 않는다")
  void executeIsIdempotentByPerformanceId() {
    // given
    Long performanceId = 1L;

    // when
    useCase.execute(performanceId, null);
    useCase.execute(performanceId, null);

    // then
    assertThat(seatLayoutRepository.findAll()).hasSize(1);
    assertThat(seatRepository.findSeatMapByPerformanceId(performanceId)).hasSize(120);
  }

  @Test
  @DisplayName("이미 좌석이 있는 공연은 좌석 수가 달라도 다시 생성하지 않는다")
  void executeSkipsWhenSeatsAlreadyExist() {
    // given
    Long performanceId = 1L;
    useCase.execute(performanceId, 120);

    // when
    useCase.execute(performanceId, 500);

    // then: 기존 공연에 소급 적용하지 않는다는 계약이다(#590). 이미 예매·선점된 좌석을 지울 수 없기 때문이다.
    assertThat(seatRepository.findSeatMapByPerformanceId(performanceId)).hasSize(120);
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
