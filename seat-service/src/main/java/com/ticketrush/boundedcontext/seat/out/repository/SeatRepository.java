package com.ticketrush.boundedcontext.seat.out.repository;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutResponse;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusCountsResponse;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.global.types.SeatStatus;
import java.time.LocalDateTime;
import java.util.List;
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

  @Query("SELECT s FROM Seat s " + "WHERE s.seatStatus = :holdStatus AND s.holdExpiredAt <= :now")
  List<Seat> findExpiredHoldSeats(
      @Param("holdStatus") SeatStatus holdStatus, @Param("now") LocalDateTime now);

  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE Seat s SET s.seatStatus = :availableStatus, s.holdExpiredAt = null "
          + "WHERE s.seatStatus = :holdStatus AND s.holdExpiredAt <= :now")
  int releaseExpiredSeats(
      @Param("availableStatus") SeatStatus availableStatus,
      @Param("holdStatus") SeatStatus holdStatus,
      @Param("now") LocalDateTime now);

  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE Seat s SET s.seatStatus = :soldStatus, s.holdExpiredAt = null "
          + "WHERE s.id = :seatId AND s.seatStatus = :holdStatus")
  int confirmSoldById(
      @Param("seatId") Long seatId,
      @Param("holdStatus") SeatStatus holdStatus,
      @Param("soldStatus") SeatStatus soldStatus);
}
