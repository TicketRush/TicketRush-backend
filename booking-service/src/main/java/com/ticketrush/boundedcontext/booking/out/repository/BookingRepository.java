package com.ticketrush.boundedcontext.booking.out.repository;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingStatsCounts;
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
   * 관리자 요약 통계 (#561). 매출까지 booking 한 번의 스캔으로 끝난다 — paid_amount를 예매가 직접 보유하므로
   * 공연 가격을 되물을 필요가 없다. 그래서 공연별 GROUP BY도, 순차 원격 호출도, 조회 실패라는 실패 모드도 없다.
   *
   * amountMissingCount는 확정을 거쳤는데 금액이 비어 있는 예매 수다. paid_amount 도입 이전에 확정된 행이
   * 백필 전까지 여기 잡힌다. SUM은 NULL을 조용히 건너뛰므로 이 값을 함께 세지 않으면 축소된 매출이 정상값처럼
   * 보인다 — 그 상태를 응답에서 구분하려고 별도 컬럼으로 뽑는다.
   */
  @Query(
      "SELECT new com.ticketrush.boundedcontext.booking.app.dto.response.BookingStatsCounts("
          // SUM은 대상 행이 없으면 0이 아니라 NULL을 낸다(COUNT와 다르다). 예매가 하나도 없는 DB에서
          // record의 long 파라미터에 그대로 들어가 NPE가 되므로 전부 COALESCE로 닫는다.
          + "COUNT(b), "
          + "COALESCE(SUM(CASE WHEN b.bookingStatus = :confirmed THEN 1 ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN b.bookingStatus = :canceled "
          + "OR b.bookingStatus = :refunded THEN 1 ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN b.bookingStatus = :confirmed "
          + "THEN b.paidAmount ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN b.bookingStatus = :confirmed AND b.paidAmount IS NULL "
          + "THEN 1 ELSE 0 END), 0)) "
          + "FROM Booking b")
  BookingStatsCounts aggregateStats(
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
