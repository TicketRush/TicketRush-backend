package com.ticketrush.boundedcontext.booking.out.repository;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPerformanceStatsRow;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

  Page<Booking> findByUserIdAndBookingStatus(
      Long userId, BookingStatus bookingStatus, Pageable pageable);

  long countByUserIdAndBookingStatus(Long userId, BookingStatus bookingStatus);

  Optional<Booking> findByBookingNumberAndUserId(String bookingNumber, Long userId);

  Optional<Booking> findByBookingNumber(String bookingNumber);

  /* 환불에 실패해 아직 해결되지 않은 예매(CONFIRMED로 복원됐고 실패 이력이 남은 건)를 관리자가 조회한다 (#391). */
  Page<Booking> findByBookingStatusAndRefundFailedAtIsNotNull(
      BookingStatus bookingStatus, Pageable pageable);

  /*
   * REFUNDING에서 cutoff 이전부터 멈춰 있는(종결 이벤트가 오지 않는) 고착 예매를 관리자가 조회한다 (#397).
   * 장애 대응 중 반복 조회하는 경로이므로 (booking_status, updated_at) 복합 인덱스로 받는다.
   */
  Page<Booking> findByBookingStatusAndUpdatedAtBefore(
      BookingStatus bookingStatus, LocalDateTime cutoff, Pageable pageable);

  @Query("SELECT b.id FROM Booking b WHERE b.bookingNumber = :bookingNumber")
  Optional<Long> findIdByBookingNumber(@Param("bookingNumber") String bookingNumber);

  /*
   * 관리자 요약 통계 (#561). 매출이 performance.price 기반이라 공연별 그룹이 어차피 필요한데, 그 김에 전체·완료·취소
   * 카운트도 같은 스캔에서 뽑아 booking을 한 번만 훑는다. performance_id가 NOT NULL이라 이 그룹은 전 행을
   * 분할하므로 행별 합계가 곧 전체 합계다.
   *
   * ORDER BY로 순서를 고정한다 — 뒤이어 공연 가격을 순차 조회하는데 예산에 걸려 끊길 때 어느 공연이 빠지는지가
   * 매 요청 달라지면 로그를 읽어 원인을 좁힐 수 없다.
   */
  @Query(
      "SELECT new com.ticketrush.boundedcontext.booking.app.dto.response"
          + ".BookingPerformanceStatsRow("
          + "b.performanceId, COUNT(b), "
          + "SUM(CASE WHEN b.bookingStatus = :confirmed THEN 1 ELSE 0 END), "
          + "SUM(CASE WHEN b.bookingStatus = :canceled "
          + "OR b.bookingStatus = :refunded THEN 1 ELSE 0 END)) "
          + "FROM Booking b "
          + "GROUP BY b.performanceId "
          + "ORDER BY b.performanceId ASC")
  List<BookingPerformanceStatsRow> aggregateStatsByPerformance(
      @Param("confirmed") BookingStatus confirmed,
      @Param("canceled") BookingStatus canceled,
      @Param("refunded") BookingStatus refunded);

  @Query(
      "SELECT b.id FROM Booking b "
          + "WHERE b.bookingStatus = :bookingStatus AND b.createdAt <= :cutoff "
          + "ORDER BY b.id ASC")
  List<Long> findExpiredPendingBookingIds(
      @Param("bookingStatus") BookingStatus bookingStatus,
      @Param("cutoff") LocalDateTime cutoff,
      Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE Booking b SET b.bookingStatus = :expiredStatus "
          + "WHERE b.id = :bookingId AND b.bookingStatus = :pendingStatus")
  int expirePendingBookingById(
      @Param("bookingId") Long bookingId,
      @Param("pendingStatus") BookingStatus pendingStatus,
      @Param("expiredStatus") BookingStatus expiredStatus);
}
