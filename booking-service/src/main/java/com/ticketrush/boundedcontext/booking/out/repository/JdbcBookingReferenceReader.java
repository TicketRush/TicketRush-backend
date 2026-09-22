package com.ticketrush.boundedcontext.booking.out.repository;

import java.util.Optional;
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

  /**
   * {@link JdbcBookingSeatStatusReader#findSeatStatus}와 같은 모양이지만 <b>타입이 없다</b> — 그쪽은 {@code
   * SeatStatus.valueOf}로 값을 세우는데 {@code PerformanceStatus}는 여기서 import할 수 없어 원문 문자열을 올린다. 판정은
   * {@link BookingReferenceReader#findPerformanceStatus} 참고.
   *
   * <p><b>{@code deleted_at}을 함께 본다.</b> {@code Performance.softDelete()}는 {@code deletedAt}만 채우고
   * {@code performance_status}는 건드리지 않으므로, 이 조건이 없으면 삭제된 {@code ON_SALE} 공연이 예매를 통과한다.
   * performance-service 쪽 같은 규칙({@code PerformanceValidateUseCase})은 엔티티의
   * {@code @SQLRestriction("deleted_at IS NULL")} 덕에 삭제 행을 자동 제외하므로, 이 조건이 빠지면 직접 읽기 경로가 내부 API보다
   * 약한 판정이 된다.
   */
  @Override
  public Optional<String> findPerformanceStatus(Long performanceId) {
    return jdbcTemplate
        .query(
            "SELECT performance_status FROM performance"
                + " WHERE performance_id = ? AND deleted_at IS NULL",
            (rs, rowNum) -> rs.getString("performance_status"),
            performanceId)
        .stream()
        .findFirst();
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
