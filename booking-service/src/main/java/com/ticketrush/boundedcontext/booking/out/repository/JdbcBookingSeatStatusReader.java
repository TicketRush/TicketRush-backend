package com.ticketrush.boundedcontext.booking.out.repository;

import com.ticketrush.global.types.SeatStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcBookingSeatStatusReader implements BookingSeatStatusReader {

  private final JdbcTemplate jdbcTemplate;

  @Override
  public Optional<SeatStatus> findSeatStatus(Long seatId, Long performanceId) {
    return jdbcTemplate
        .query(
            "SELECT seat_status FROM seat WHERE seat_id = ? AND performance_id = ?",
            (rs, rowNum) -> SeatStatus.valueOf(rs.getString("seat_status")),
            seatId,
            performanceId)
        .stream()
        .findFirst();
  }
}
