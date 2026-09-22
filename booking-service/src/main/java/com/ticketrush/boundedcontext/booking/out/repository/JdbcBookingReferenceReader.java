package com.ticketrush.boundedcontext.booking.out.repository;

import com.ticketrush.global.types.PerformanceStatus;
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
   * {@link JdbcBookingSeatStatusReader#findSeatStatus}와 같은 꼴이다 — {@code valueOf}로 타입을 세우므로, 컬럼에
   * enum 에 없는 값이 들어 있으면 조용히 통과하지 않고 예외로 드러난다.
   *
   * <p><b>{@code deleted_at}을 함께 본다.</b> {@code Performance.softDelete()}는 {@code deletedAt}만 채우고
   * {@code performance_status}는 건드리지 않으므로, 이 조건이 없으면 삭제된 {@code ON_SALE} 공연이 예매를 통과한다.
   * performance-service 쪽 같은 규칙({@code PerformanceValidateUseCase})은 엔티티의
   * {@code @SQLRestriction("deleted_at IS NULL")} 덕에 삭제 행을 자동 제외하므로, 이 조건이 빠지면 직접 읽기 경로가 내부 API보다
   * 약한 판정이 된다.
   */
  @Override
  public Optional<PerformanceStatus> findPerformanceStatus(Long performanceId) {
    return jdbcTemplate
        .query(
            "SELECT performance_status FROM performance"
                + " WHERE performance_id = ? AND deleted_at IS NULL",
            (rs, rowNum) -> PerformanceStatus.valueOf(rs.getString("performance_status")),
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
