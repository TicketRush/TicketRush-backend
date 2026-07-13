package com.ticketrush.boundedcontext.seat.out.repository;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatNumberResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsResponse;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

  Long countByPerformanceId(Long performanceId);

  Long countByPerformanceIdAndSeatStatus(Long performanceId, SeatStatus seatStatus);

  @Query(
      "SELECT new com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsResponse("
          + "COUNT(s), "
          + "COUNT(CASE WHEN s.seatStatus = :availableStatus "
          + "OR (s.seatStatus = :holdStatus AND s.holdExpiredAt <= :now) THEN 1 END), "
          + "COUNT(CASE WHEN s.seatStatus = :soldStatus THEN 1 END), "
          + "COUNT(CASE WHEN s.seatStatus = :holdStatus AND s.holdExpiredAt > :now THEN 1 END)) "
          + "FROM Seat s "
          + "WHERE s.performanceId = :performanceId")
  SeatStatusCountsResponse getStatusCountsByPerformanceIdAndStatuses(
      @Param("performanceId") Long performanceId,
      @Param("availableStatus") SeatStatus availableStatus,
      @Param("soldStatus") SeatStatus soldStatus,
      @Param("holdStatus") SeatStatus holdStatus,
      @Param("now") LocalDateTime now);

  default SeatStatusCountsResponse getStatusCountsByPerformanceId(
      Long performanceId, LocalDateTime now) {
    return getStatusCountsByPerformanceIdAndStatuses(
        performanceId, SeatStatus.AVAILABLE, SeatStatus.SOLD, SeatStatus.HOLD, now);
  }

  @Query(
      "SELECT new com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutResponse("
          + "s.id, sl.id, s.seatNumber, s.seatStatus, s.holdExpiredAt) "
          + "FROM Seat s "
          + "JOIN SeatLayout sl ON s.seatLayoutId = sl.id "
          + "WHERE s.performanceId = :performanceId")
  List<SeatLayoutResponse> findSeatLayoutsByPerformanceId(
      @Param("performanceId") Long performanceId);

  @Query(
      "SELECT new com.ticketrush.boundedcontext.seat.app.dto.response.SeatNumberResponse("
          + "s.id, s.seatNumber) "
          + "FROM Seat s "
          + "WHERE s.id IN :seatIds")
  List<SeatNumberResponse> findSeatNumbersByIdIn(@Param("seatIds") List<Long> seatIds);

  @Query(
      "SELECT s FROM Seat s "
          + "WHERE s.seatStatus = :holdStatus AND s.holdExpiredAt <= :now "
          + "ORDER BY s.id ASC")
  List<Seat> findExpiredHoldSeats(
      @Param("holdStatus") SeatStatus holdStatus,
      @Param("now") LocalDateTime now,
      Pageable pageable);

  /*
   * 만료 HOLD 해제를 가드가 달린 조건부 UPDATE로 처리한다.
   *
   * 해제 경로(스케줄러 fallback, Redis 만료 리스너)는 좌석을 조회한 뒤 엔티티를 고쳐 더티 체킹으로 UPDATE하는데,
   * 그 UPDATE의 WHERE는 seat_id뿐이다. 조회와 flush 사이에 다른 트랜잭션이 끼어들면 남의 상태를 덮어쓴다. 조회
   * 시점의 상태 검사로는 막지 못한다. 그 검사도 결국 같은 조회 스냅샷을 보기 때문이다. 가드를 UPDATE 자체에 실어야
   * 원자적이다. 막아야 할 인터리브는 둘이다.
   *
   * 1. 결제 확정(confirmSoldById)이 먼저 커밋 -> 팔린 좌석(SOLD)을 AVAILABLE로 되돌려 재판매한다.
   *    -> seatStatus = HOLD 가드가 막는다.
   * 2. 다른 해제 경로가 먼저 커밋하고 그 좌석이 곧바로 재선점된다(ABA). 상태는 다시 HOLD지만 예매도 만료 시각도
   *    바뀌었다 -> 살아있는 남의 홀드를 풀어버린다. 상태 가드만으로는 통과한다.
   *    -> bookingNumber(내가 본 그 선점인가) + holdExpiredAt <= now(여전히 만료 상태인가)가 막는다.
   *
   * confirmSoldById가 seatStatus 하나가 아니라 bookingNumber까지 함께 거는 것과 같은 이유다.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE Seat s SET s.seatStatus = :availableStatus, s.holdExpiredAt = null, "
          + "s.bookingNumber = null "
          + "WHERE s.id = :seatId AND s.seatStatus = :holdStatus "
          + "AND s.bookingNumber = :bookingNumber AND s.holdExpiredAt <= :now")
  int releaseExpiredHoldById(
      @Param("seatId") Long seatId,
      @Param("bookingNumber") String bookingNumber,
      @Param("now") LocalDateTime now,
      @Param("holdStatus") SeatStatus holdStatus,
      @Param("availableStatus") SeatStatus availableStatus);

  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE Seat s SET s.seatStatus = :soldStatus, s.holdExpiredAt = null "
          + "WHERE s.id = :seatId "
          + "AND s.bookingNumber = :bookingNumber "
          + "AND s.seatStatus = :holdStatus")
  int confirmSoldById(
      @Param("seatId") Long seatId,
      @Param("bookingNumber") String bookingNumber,
      @Param("holdStatus") SeatStatus holdStatus,
      @Param("soldStatus") SeatStatus soldStatus);

  @Query("select count(s) from Seat s where s.seatStatus = :hold and s.holdExpiredAt > :now")
  long countHeldSeats(@Param("hold") SeatStatus hold, @Param("now") LocalDateTime now);
}
