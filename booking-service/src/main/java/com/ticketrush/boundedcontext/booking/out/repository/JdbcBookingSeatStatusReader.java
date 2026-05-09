package com.ticketrush.boundedcontext.booking.out.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcBookingSeatStatusReader implements BookingSeatStatusReader {

  private final JdbcTemplate jdbcTemplate;

  @Override
  public String findSeatStatus(Long seatId, Long performanceId) {
    return jdbcTemplate.queryForObject(
        "SELECT seat_status FROM seat WHERE seat_id = ? AND performance_id = ?",
        String.class,
        seatId,
        performanceId);
  }
}
