package com.ticketrush.boundedcontext.booking.out.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcBookingReferenceReader implements BookingReferenceReader {

  private final JdbcTemplate jdbcTemplate;

  @Override
  public boolean existsUserById(Long userId) {
    return exists("SELECT COUNT(*) FROM `user` WHERE id = ?", userId);
  }

  @Override
  public boolean existsPerformanceById(Long performanceId) {
    return exists("SELECT COUNT(*) FROM performance WHERE performance_id = ?", performanceId);
  }

  @Override
  public boolean existsSeatByIdAndPerformanceId(Long seatId, Long performanceId) {
    return exists(
        "SELECT COUNT(*) FROM seat WHERE seat_id = ? AND performance_id = ?",
        seatId,
        performanceId);
  }

  private boolean exists(String sql, Long id) {
    Long count = jdbcTemplate.queryForObject(sql, Long.class, id);
    return count != null && count > 0L;
  }

  private boolean exists(String sql, Long firstId, Long secondId) {
    Long count = jdbcTemplate.queryForObject(sql, Long.class, firstId, secondId);
    return count != null && count > 0L;
  }
}
