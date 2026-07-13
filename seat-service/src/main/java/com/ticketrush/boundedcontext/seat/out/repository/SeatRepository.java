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
   * HOLD 해제를 상태 가드가 달린 조건부 UPDATE로 처리한다.
   *
   * 해제 경로(스케줄러 fallback, Redis 만료 리스너)는 좌석을 조회한 뒤 엔티티를 고쳐 더티 체킹으로 UPDATE하는데,
   * 그 UPDATE의 WHERE는 seat_id뿐이다. 조회와 flush 사이에 결제 확정(confirmSoldById)이 끼어들면 이미 SOLD가 된
   * 좌석을 AVAILABLE로 되돌려 버린다(lost update) — 팔린 좌석이 풀려 재판매된다. 조회 시점의 상태 검사로는 막지
   * 못한다. 그 검사도 결국 조회 스냅샷을 보기 때문이다. 가드를 UPDATE 자체에 실어야 원자적이다.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE Seat s SET s.seatStatus = :availableStatus, s.holdExpiredAt = null, "
          + "s.bookingNumber = null "
          + "WHERE s.id = :seatId AND s.seatStatus = :holdStatus")
  int releaseHoldById(
      @Param("seatId") Long seatId,
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
