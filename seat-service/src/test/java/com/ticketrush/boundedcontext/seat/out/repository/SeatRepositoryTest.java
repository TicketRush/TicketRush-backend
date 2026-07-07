package com.ticketrush.boundedcontext.seat.out.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutResponse;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.domain.entity.SeatLayout;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
class SeatRepositoryTest {

  @Autowired private SeatRepository seatRepository;

  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("Seat과 마스터 SeatLayout을 조인하여 해당하는 공연의 좌석 정보(DTO)만 조회한다")
  void findSeatLayoutsByPerformanceId() {
    // given
    Long targetPerformanceId = 1L;
    Long otherPerformanceId = 99L;

    // 1. 공연당 1개의 마스터 SeatLayout 세팅 (1대N 구조 반영)
    SeatLayout targetLayout =
        SeatLayout.builder().performanceId(targetPerformanceId).totalRows(10).maxCols(12).build();
    SeatLayout otherLayout =
        SeatLayout.builder().performanceId(otherPerformanceId).totalRows(5).maxCols(5).build();

    targetLayout = entityManager.persist(targetLayout);
    otherLayout = entityManager.persist(otherLayout);

    // 2. 단일 마스터 레이아웃을 참조하는 여러 개의 Seat 데이터 세팅
    Seat seat1 =
        Seat.builder()
            .seatLayoutId(targetLayout.getId())
            .performanceId(targetPerformanceId)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.AVAILABLE)
            .build();
    Seat seat2 =
        Seat.builder()
            .seatLayoutId(targetLayout.getId())
            .performanceId(targetPerformanceId)
            .seatNumber("A-2")
            .seatStatus(SeatStatus.AVAILABLE)
            .build();
    Seat otherSeat =
        Seat.builder()
            .seatLayoutId(otherLayout.getId())
            .performanceId(otherPerformanceId)
            .seatNumber("A-1")
            .seatStatus(SeatStatus.AVAILABLE)
            .build();

    entityManager.persist(seat1);
    entityManager.persist(seat2);
    entityManager.persist(otherSeat);

    // DB에 쿼리 반영 후 1차 캐시 초기화
    entityManager.flush();
    entityManager.clear();

    // when
    List<SeatLayoutResponse> result =
        seatRepository.findSeatLayoutsByPerformanceId(targetPerformanceId);

    // then
    assertThat(result)
        .hasSize(2)
        .extracting(
            SeatLayoutResponse::seatId,
            SeatLayoutResponse::seatLayoutId,
            SeatLayoutResponse::seatNumber,
            SeatLayoutResponse::seatStatus)
        .containsExactlyInAnyOrder(
            tuple(seat1.getId(), targetLayout.getId(), "A-1", SeatStatus.AVAILABLE),
            tuple(seat2.getId(), targetLayout.getId(), "A-2", SeatStatus.AVAILABLE));
  }

  @Test
  @DisplayName("벌크 업데이트 쿼리로 시간이 만료된 HOLD 상태의 좌석을 AVAILABLE로 변경한다")
  void releaseExpiredSeats_ChangesStatusToAvailable() {
    // given
    LocalDateTime now = LocalDateTime.now();

    // 1. 이미 만료된 좌석 (HOLD 상태) -> 업데이트 대상 O
    Seat expiredHoldSeat1 =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(100L)
            .seatNumber("A1")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(now.minusMinutes(1))
            .build();

    Seat expiredHoldSeat2 =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(100L)
            .seatNumber("A2")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(now.minusMinutes(5))
            .build();

    // 2. 만료되지 않은 좌석 (HOLD 상태) -> 업데이트 대상 X
    Seat validHoldSeat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(100L)
            .seatNumber("A3")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(now.plusMinutes(5))
            .build();

    // 3. 이미 결제 완료된 좌석 (SOLD 상태) -> 업데이트 대상 X
    Seat soldSeat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(100L)
            .seatNumber("A4")
            .seatStatus(SeatStatus.SOLD)
            .holdExpiredAt(now.minusMinutes(1))
            .build();

    seatRepository.saveAll(List.of(expiredHoldSeat1, expiredHoldSeat2, validHoldSeat, soldSeat));

    // when
    int updatedCount =
        seatRepository.releaseExpiredSeats(SeatStatus.AVAILABLE, SeatStatus.HOLD, now);

    // then
    assertThat(updatedCount).isEqualTo(2); // 만료된 HOLD 좌석 2개만 업데이트되어야 함

    // DB에서 다시 조회하여 실제 상태 검증 (영속성 컨텍스트를 거치지 않고 DB에서 직접 확인하기 위해 벌크 연산 결과 검증)
    Seat updatedSeat1 = seatRepository.findById(expiredHoldSeat1.getId()).orElseThrow();
    Seat validSeat = seatRepository.findById(validHoldSeat.getId()).orElseThrow();
    Seat updatedSoldSeat = seatRepository.findById(soldSeat.getId()).orElseThrow();

    assertThat(updatedSeat1.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE); // AVAILABLE로 변경됨
    assertThat(validSeat.getSeatStatus()).isEqualTo(SeatStatus.HOLD); // 변경되지 않음
    assertThat(updatedSoldSeat.getSeatStatus()).isEqualTo(SeatStatus.SOLD); // 변경되지 않음
  }

  @Test
  @DisplayName("만료된 HOLD 좌석을 id 오름차순으로 페이지 크기만큼만(청크) 조회하고 비만료 HOLD/SOLD는 제외한다")
  void findExpiredHoldSeats_ReturnsChunkOrderedById() {
    // given
    LocalDateTime now = LocalDateTime.now();

    // 만료된 HOLD 좌석 3개 -> 조회 대상 O (저장 순서를 뒤섞어 id ASC 정렬을 검증)
    Seat expiredHoldSeat1 =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(100L)
            .seatNumber("A1")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(now.minusMinutes(1))
            .build();
    Seat expiredHoldSeat2 =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(100L)
            .seatNumber("A2")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(now.minusMinutes(3))
            .build();
    Seat expiredHoldSeat3 =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(100L)
            .seatNumber("A3")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(now.minusMinutes(5))
            .build();

    // 만료되지 않은 HOLD 좌석 -> 조회 대상 X
    Seat validHoldSeat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(100L)
            .seatNumber("A4")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(now.plusMinutes(5))
            .build();

    // 이미 결제 완료된 SOLD 좌석 -> 조회 대상 X
    Seat soldSeat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(100L)
            .seatNumber("A5")
            .seatStatus(SeatStatus.SOLD)
            .holdExpiredAt(now.minusMinutes(1))
            .build();

    seatRepository.saveAll(
        List.of(expiredHoldSeat1, expiredHoldSeat2, expiredHoldSeat3, validHoldSeat, soldSeat));

    // when: 청크 크기 2로 상위 페이지 조회
    List<Seat> firstChunk =
        seatRepository.findExpiredHoldSeats(SeatStatus.HOLD, now, PageRequest.of(0, 2));

    // then: 만료 HOLD만, 정확히 청크 크기(2)만큼, id 오름차순으로 반환
    assertThat(firstChunk).hasSize(2);
    assertThat(firstChunk)
        .extracting(Seat::getId)
        .containsExactly(expiredHoldSeat1.getId(), expiredHoldSeat2.getId());
    assertThat(firstChunk).allMatch(seat -> seat.getSeatStatus() == SeatStatus.HOLD);
  }

  @Test
  @DisplayName("공연 ID로 전체 좌석 수와 상태별 좌석 수를 계산하고 만료된 HOLD는 예매 가능으로 계산한다")
  void getStatusCountsByPerformanceId_ReturnsStatusCounts() {
    // given
    Long targetPerformanceId = 1L;
    Long otherPerformanceId = 99L;
    LocalDateTime now = LocalDateTime.now();

    Seat availableSeat1 =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(targetPerformanceId)
            .seatNumber("A1")
            .seatStatus(SeatStatus.AVAILABLE)
            .build();
    Seat availableSeat2 =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(targetPerformanceId)
            .seatNumber("A2")
            .seatStatus(SeatStatus.AVAILABLE)
            .build();
    Seat holdSeat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(targetPerformanceId)
            .seatNumber("A3")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(now.plusMinutes(5))
            .build();
    Seat soldSeat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(targetPerformanceId)
            .seatNumber("A4")
            .seatStatus(SeatStatus.SOLD)
            .build();
    Seat expiredHoldSeat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(targetPerformanceId)
            .seatNumber("A5")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(now.minusMinutes(1))
            .build();
    Seat otherPerformanceSeat =
        Seat.builder()
            .seatLayoutId(2L)
            .performanceId(otherPerformanceId)
            .seatNumber("A1")
            .seatStatus(SeatStatus.AVAILABLE)
            .build();

    seatRepository.saveAll(
        List.of(
            availableSeat1,
            availableSeat2,
            holdSeat,
            soldSeat,
            expiredHoldSeat,
            otherPerformanceSeat));

    // when
    var response = seatRepository.getStatusCountsByPerformanceId(targetPerformanceId, now);

    // then
    assertThat(response.totalCount()).isEqualTo(5L);
    assertThat(response.availableCount()).isEqualTo(3L);
    assertThat(response.soldCount()).isEqualTo(1L);
    assertThat(response.holdCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("공연 ID에 해당하는 좌석이 없으면 모든 카운트를 0으로 반환한다")
  void getStatusCountsByPerformanceId_ReturnsZeroWhenNoSeatsExist() {
    // when
    var response = seatRepository.getStatusCountsByPerformanceId(1L, LocalDateTime.now());

    // then
    assertThat(response.totalCount()).isZero();
    assertThat(response.availableCount()).isZero();
    assertThat(response.soldCount()).isZero();
    assertThat(response.holdCount()).isZero();
  }

  @Test
  @DisplayName("HOLD 상태인 좌석을 SOLD 상태로 변경하고 선점 만료 시간을 초기화한다")
  void confirmSoldById_ChangesHoldSeatToSold() {
    // given
    LocalDateTime holdExpiredAt = LocalDateTime.now().plusMinutes(5);

    Seat holdSeat1 =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(100L)
            .seatNumber("A1")
            .seatStatus(SeatStatus.HOLD)
            .holdExpiredAt(holdExpiredAt)
            .bookingNumber("BOOK-1234")
            .build();

    Seat availableSeat =
        Seat.builder()
            .seatLayoutId(1L)
            .performanceId(100L)
            .seatNumber("A3")
            .seatStatus(SeatStatus.AVAILABLE)
            .build();

    seatRepository.saveAll(List.of(holdSeat1, availableSeat));
    entityManager.flush();
    entityManager.clear();

    // when
    int updatedCount =
        seatRepository.confirmSoldById(
            holdSeat1.getId(), "BOOK-1234", SeatStatus.HOLD, SeatStatus.SOLD);

    // then
    assertThat(updatedCount).isEqualTo(1);

    Seat updatedSeat1 = seatRepository.findById(holdSeat1.getId()).orElseThrow();
    Seat notUpdatedSeat = seatRepository.findById(availableSeat.getId()).orElseThrow();

    assertThat(updatedSeat1.getSeatStatus()).isEqualTo(SeatStatus.SOLD);
    assertThat(updatedSeat1.getHoldExpiredAt()).isNull();
    assertThat(notUpdatedSeat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
  }
}
