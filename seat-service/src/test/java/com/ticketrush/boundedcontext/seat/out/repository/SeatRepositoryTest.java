package com.ticketrush.boundedcontext.seat.out.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatMapItemResponse;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@DataJpaTest
class SeatRepositoryTest {

  @Autowired private SeatRepository seatRepository;

  @Autowired private TestEntityManager entityManager;

  @Test
  @DisplayName("공연 ID로 해당 공연의 좌석 정보(DTO)만 조회한다")
  void findSeatMapByPerformanceId() {
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
    List<SeatMapItemResponse> result =
        seatRepository.findSeatMapByPerformanceId(targetPerformanceId);

    // then
    assertThat(result)
        .hasSize(2)
        .extracting(
            SeatMapItemResponse::seatId,
            SeatMapItemResponse::seatLayoutId,
            SeatMapItemResponse::seatNumber,
            SeatMapItemResponse::seatStatus)
        .containsExactlyInAnyOrder(
            tuple(seat1.getId(), targetLayout.getId(), "A-1", SeatStatus.AVAILABLE),
            tuple(seat2.getId(), targetLayout.getId(), "A-2", SeatStatus.AVAILABLE));
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

  @Test
  @DisplayName("조회 스냅샷의 만료 시각과 일치하는 HOLD 좌석을 AVAILABLE로 해제한다")
  void releaseExpiredHoldById_ReleasesExpiredHoldSeat() {
    // given
    Seat holdSeat =
        buildSeat("A1", SeatStatus.HOLD, LocalDateTime.now().minusMinutes(1), "BOOK-1234");
    seatRepository.save(holdSeat);
    entityManager.flush();
    entityManager.clear();

    // 가드에는 DB에서 읽어온 값을 넘긴다. datetime(6)은 마이크로초라 앱이 만든 나노초 값과 다르다.
    LocalDateTime persistedExpiredAt =
        seatRepository.findById(holdSeat.getId()).orElseThrow().getHoldExpiredAt();

    // when
    int released =
        seatRepository.releaseExpiredHoldById(
            holdSeat.getId(), persistedExpiredAt, SeatStatus.HOLD, SeatStatus.AVAILABLE);

    // then
    assertThat(released).isEqualTo(1);

    Seat updated = seatRepository.findById(holdSeat.getId()).orElseThrow();
    assertThat(updated.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE);
    assertThat(updated.getHoldExpiredAt()).isNull();
    assertThat(updated.getBookingNumber()).isNull();
  }

  @Test
  @DisplayName("bookingNumber가 없는 HOLD 좌석(#95 이전 데이터)도 해제된다")
  void releaseExpiredHoldById_ReleasesHoldSeatWithoutBookingNumber() {
    // given: #95 이전의 hold(expiredAt)는 bookingNumber 없이 HOLD가 가능했다. 그 시절 행이 기존 DB에 남아
    // 있을 수 있고, SeatHoldExpiredPublisher도 이 경우를 방어하고 있다. 가드가 이런 좌석을 영구히 해제 불가로
    // 만들면 청크 루프의 앞자리를 점유해 뒤의 만료 좌석까지 굶긴다.
    Seat seat = buildSeat("A5", SeatStatus.HOLD, LocalDateTime.now().minusMinutes(1), null);
    seatRepository.save(seat);
    entityManager.flush();
    entityManager.clear();

    LocalDateTime persistedExpiredAt =
        seatRepository.findById(seat.getId()).orElseThrow().getHoldExpiredAt();

    // when
    int released =
        seatRepository.releaseExpiredHoldById(
            seat.getId(), persistedExpiredAt, SeatStatus.HOLD, SeatStatus.AVAILABLE);

    // then
    assertThat(released).isEqualTo(1);
    assertThat(seatRepository.findById(seat.getId()).orElseThrow().getSeatStatus())
        .isEqualTo(SeatStatus.AVAILABLE);
  }

  @Test
  @DisplayName("조회 이후 결제가 확정된(SOLD) 좌석은 해제하지 않는다")
  void releaseExpiredHoldById_DoesNotRevertSoldSeat() {
    // given: 스케줄러가 만료 HOLD로 조회한 뒤 confirmSoldById가 SOLD로 확정한 좌석
    // (confirmSoldById는 hold_expired_at을 null로 지우지만, 여기선 상태 가드가 먼저 막는 것을 본다)
    LocalDateTime snapshotExpiredAt = LocalDateTime.now().minusMinutes(1);
    Seat soldSeat = buildSeat("A2", SeatStatus.SOLD, snapshotExpiredAt, "BOOK-1234");
    seatRepository.save(soldSeat);
    entityManager.flush();
    entityManager.clear();

    // when: 스케줄러가 낡은 조회 스냅샷을 근거로 해제를 시도한다
    int released =
        seatRepository.releaseExpiredHoldById(
            soldSeat.getId(), snapshotExpiredAt, SeatStatus.HOLD, SeatStatus.AVAILABLE);

    // then: 팔린 좌석이 풀려 재판매되면 안 된다
    assertThat(released).isZero();

    Seat untouched = seatRepository.findById(soldSeat.getId()).orElseThrow();
    assertThat(untouched.getSeatStatus()).isEqualTo(SeatStatus.SOLD);
    assertThat(untouched.getBookingNumber()).isEqualTo("BOOK-1234");
  }

  @Test
  @DisplayName("해제 후 다른 예매로 재선점된 좌석(ABA)은 해제하지 않는다")
  void releaseExpiredHoldById_DoesNotReleaseReheldSeatOfAnotherBooking() {
    // given: 스케줄러가 (HOLD, 만료 1분 전)으로 조회했지만, 그 사이 좌석이 풀렸다가 다른 예매로 재선점됐다.
    // 상태는 다시 HOLD라 상태 가드는 통과한다. 재선점은 만료 시각을 반드시 미래로 새로 쓰므로, 스냅샷 만료
    // 시각과의 동등 비교만이 이 인터리브를 막는다.
    Seat reheldSeat =
        buildSeat("A3", SeatStatus.HOLD, LocalDateTime.now().plusMinutes(10), "BOOK-2222");
    seatRepository.save(reheldSeat);
    entityManager.flush();
    entityManager.clear();

    // when: 스케줄러가 낡은 스냅샷(만료 1분 전)을 근거로 해제를 시도한다
    int released =
        seatRepository.releaseExpiredHoldById(
            reheldSeat.getId(),
            LocalDateTime.now().minusMinutes(1),
            SeatStatus.HOLD,
            SeatStatus.AVAILABLE);

    // then: 남의 살아있는 선점이 풀리면 안 된다
    assertThat(released).isZero();

    Seat untouched = seatRepository.findById(reheldSeat.getId()).orElseThrow();
    assertThat(untouched.getSeatStatus()).isEqualTo(SeatStatus.HOLD);
    assertThat(untouched.getBookingNumber()).isEqualTo("BOOK-2222");
    assertThat(untouched.getHoldExpiredAt()).isNotNull();
  }

  @Test
  @DisplayName("stale 버전 스냅샷으로 HOLD를 쓰면 낙관적 락 충돌로 거부된다 (#427)")
  void hold_WithStaleVersion_ThrowsOptimisticLockingFailure() {
    // given: Redis 락이 유실돼 두 처리가 같은 AVAILABLE 좌석을 동시에 읽은 상황.
    // 실제 런타임 경합(두 영속성 컨텍스트의 동시 flush) 대신 낡은 스냅샷으로 쓰기를 시뮬레이션한다 —
    // 이 파일의 ABA 가드 테스트들과 같은 방식이다. 검증 대상은 version 매핑이 실제로 UPDATE 가드로 걸리는지다.
    LocalDateTime holdExpiredAt = LocalDateTime.now().plusMinutes(5);
    Seat stale = seatRepository.save(buildSeat("A4", SeatStatus.AVAILABLE, null, null));
    entityManager.flush();
    entityManager.detach(stale);

    // 먼저 커밋한 쪽이 버전을 올린다
    Seat winner = seatRepository.findById(stale.getId()).orElseThrow();
    winner.hold(holdExpiredAt, "BOOK-WINNER");
    seatRepository.saveAndFlush(winner);
    entityManager.detach(winner);

    // when & then: 늦은 쪽은 메모리상 AVAILABLE이라 hold() 검증은 통과하지만, DB가 stale 버전을 거부한다
    stale.hold(holdExpiredAt, "BOOK-LOSER");
    assertThatThrownBy(() -> seatRepository.saveAndFlush(stale))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    // 승자의 선점만 남는다
    entityManager.clear();
    Seat persisted = seatRepository.findById(stale.getId()).orElseThrow();
    assertThat(persisted.getSeatStatus()).isEqualTo(SeatStatus.HOLD);
    assertThat(persisted.getBookingNumber()).isEqualTo("BOOK-WINNER");
  }

  @Test
  @DisplayName("미만료 HOLD와 만료 HOLD를 상호배타적으로 세고, 만료 시각이 기준시와 같으면 만료 쪽으로 센다 (#345)")
  void countHeldSeats_AndCountExpiredHoldSeats_SplitAtSameBoundary() {
    // given: countHeldSeats(> now)와 countExpiredHoldSeats(<= now)가 같은 경계에서 갈려야
    // 두 카운트 합이 전체 HOLD가 되고, 적체 게이지가 스케줄러가 집어가는 집합(findExpiredHoldSeats)과 일치한다.
    final Seat boundary =
        seatRepository.save(buildSeat("A1", SeatStatus.HOLD, LocalDateTime.now(), "BOOK-1"));
    seatRepository.save(
        buildSeat("A2", SeatStatus.HOLD, LocalDateTime.now().minusMinutes(1), "BOOK-2"));
    seatRepository.save(
        buildSeat("A3", SeatStatus.HOLD, LocalDateTime.now().plusMinutes(5), "BOOK-3"));
    // HOLD가 아닌 좌석은 어느 쪽에도 들어가지 않는다
    seatRepository.save(
        buildSeat("A4", SeatStatus.SOLD, LocalDateTime.now().minusMinutes(1), "BOOK-4"));
    entityManager.flush();
    entityManager.clear();

    // 기준시는 DB에 저장된 값을 되읽어 쓴다. datetime(6)은 마이크로초라 앱이 만든 나노초 값을 그대로 쓰면
    // 경계 좌석이 어느 쪽에도(또는 양쪽에) 걸릴 수 있다.
    LocalDateTime now = seatRepository.findById(boundary.getId()).orElseThrow().getHoldExpiredAt();

    // when
    long held = seatRepository.countHeldSeats(SeatStatus.HOLD, now);
    long expiredBacklog = seatRepository.countExpiredHoldSeats(SeatStatus.HOLD, now);

    // then: 경계 좌석은 만료 쪽으로 간다
    assertThat(held).isEqualTo(1L);
    assertThat(expiredBacklog).isEqualTo(2L);
    assertThat(seatRepository.findExpiredHoldSeats(SeatStatus.HOLD, now, PageRequest.of(0, 10)))
        .hasSize((int) expiredBacklog);
  }

  private Seat buildSeat(
      String seatNumber, SeatStatus status, LocalDateTime holdExpiredAt, String bookingNumber) {
    return Seat.builder()
        .seatLayoutId(1L)
        .performanceId(100L)
        .seatNumber(seatNumber)
        .seatStatus(status)
        .holdExpiredAt(holdExpiredAt)
        .bookingNumber(bookingNumber)
        .build();
  }
}
