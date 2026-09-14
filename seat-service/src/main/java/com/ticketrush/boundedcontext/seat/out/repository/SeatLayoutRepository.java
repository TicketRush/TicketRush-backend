package com.ticketrush.boundedcontext.seat.out.repository;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutSizeResponse;
import com.ticketrush.boundedcontext.seat.domain.entity.SeatLayout;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatLayoutRepository extends JpaRepository<SeatLayout, Long> {

  boolean existsByPerformanceId(Long performanceId);

  /** 좌석맵의 배치 크기(#645). {@code uk_seat_layout_performance_id}로 공연당 최대 1건이다. */
  @Query(
      "SELECT new com.ticketrush.boundedcontext.seat.app.dto.response.SeatLayoutSizeResponse("
          + "l.totalRows, l.maxCols) "
          + "FROM SeatLayout l "
          + "WHERE l.performanceId = :performanceId")
  Optional<SeatLayoutSizeResponse> findSizeByPerformanceId(
      @Param("performanceId") Long performanceId);
}
