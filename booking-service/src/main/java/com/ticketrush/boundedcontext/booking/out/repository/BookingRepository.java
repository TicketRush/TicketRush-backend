package com.ticketrush.boundedcontext.booking.out.repository;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingDailyRevenueRow;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPerformanceStatsRow;
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

  /*
   * 공연별 예매·매출 집계 (#563 관리자 대시보드). 요약(aggregateStats)과 같은 모집단(CONFIRMED)을 보되 공연으로 묶는다.
   * 두 쿼리의 상태 조건이 갈리면 대시보드의 카드 합과 공연 목록 합이 어긋난다.
   *
   * WHERE 없는 전건 GROUP BY다. 예매는 오픈런 트래픽을 받는 쓰기 핫패스라, 호출 빈도가 극히 낮은 관리자 조회를
   * 위해 인덱스를 얹으면 그 비용을 모든 예매 INSERT/UPDATE가 상시 부담한다.
   *
   * 실측(로컬 MySQL, booking 3,000행 / CONFIRMED 1,000행):
   *   Sort: performance_id <- Table scan on <temporary> <- Aggregate using temporary table
   *     <- Table scan on b (cost=305 rows=3000)
   * (performance_id, booking_status) 인덱스를 얹으면 임시 테이블 그룹핑을 인덱스 순회로 바꿀 수 있으나,
   * 관리자 대시보드 1회 호출당 한 번인 비용을 줄이자고 모든 예매 쓰기에 인덱스 유지 비용을 얹는 교환이다.
   * 공연 수가 아니라 예매 행 수에 비례해 커지므로, 느려지면 그때 이 수치와 대조해 추가한다.
   *
   * ORDER BY는 호출자를 위해서가 아니라 정렬 없는 GROUP BY의 순서가 실행 계획에 따라 흔들려 테스트가 간헐
   * 실패하는 것을 막으려고 명시한다.
   */
  @Query(
      "SELECT new com.ticketrush.boundedcontext.booking.app.dto.response"
          + ".BookingPerformanceStatsRow("
          + "b.performanceId, "
          // SUM은 대상 행이 없으면 NULL이라 record의 long에서 NPE가 된다. aggregateStats와 같은 이유로 COALESCE.
          + "COALESCE(SUM(CASE WHEN b.bookingStatus = :confirmed THEN 1 ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN b.bookingStatus = :confirmed "
          + "THEN b.paidAmount ELSE 0 END), 0)) "
          + "FROM Booking b "
          + "GROUP BY b.performanceId "
          + "ORDER BY b.performanceId ASC")
  List<BookingPerformanceStatsRow> aggregateStatsByPerformance(
      @Param("confirmed") BookingStatus confirmed);

  /*
   * 지정한 공연들만 집계한다 (#590 관리자 공연 목록). 세는 규칙은 전건 버전과 같은 식을 그대로 쓴다.
   *
   * 관리자 공연 목록은 페이지마다 이 집계를 부른다. 전건 버전을 쓰면 페이지를 넘길 때마다 booking 전체를
   * 훑는데, 그건 오픈런 트래픽을 받는 쓰기 핫패스다.
   *
   * 실측(로컬 MySQL 8.0, booking 3,000행 / CONFIRMED 1,000행, 공연 50건 지정):
   *   전건: Sort <- Table scan on <temporary> <- Aggregate using temporary table
   *           <- Table scan on b (cost=303 rows=2980)  -> 3,000행 읽고 101그룹, 1.28ms
   *   IN  : Sort <- Table scan on <temporary> <- Aggregate using temporary table
   *           <- Filter: performance_id in (...) (cost=303 rows=1490)
   *             <- Table scan on b (cost=303 rows=2980)  -> 3,000행 읽고 50그룹, 1.08ms
   *
   * 스캔량은 줄지 않는다. booking에는 performance_id를 선두로 하는 인덱스가 없어(있는 것은 user_id·status
   * 조합과 status·updated_at 조합뿐이다) IN이 range scan이 되지 못하고 필터로만 걸린다. 줄어드는 것은 임시
   * 테이블의 그룹 수와 정렬 대상, 그리고 응답 행 수다. seat 쪽은 선두 컬럼이 맞아 읽는 행 자체가 절반으로
   * 주는데, 여기는 그렇지 않다는 뜻이다. 그래도 인덱스를 얹지 않는 이유는 위와 같다 — 관리자 저빈도 조회를
   * 위해 모든 예매 INSERT/UPDATE에 인덱스 유지 비용을 상시 얹는 교환이 맞지 않는다. 예매 행 수가 늘어 이
   * 조회가 문제가 되면 그때 이 수치와 대조해 추가한다.
   *
   * 빈 목록을 넘기면 IN ()이 되어 JPQL이 성립하지 않는다. 호출자(BookingGetInternalStatsUseCase)가 막는다.
   */
  @Query(
      "SELECT new com.ticketrush.boundedcontext.booking.app.dto.response"
          + ".BookingPerformanceStatsRow("
          + "b.performanceId, "
          + "COALESCE(SUM(CASE WHEN b.bookingStatus = :confirmed THEN 1 ELSE 0 END), 0), "
          + "COALESCE(SUM(CASE WHEN b.bookingStatus = :confirmed "
          + "THEN b.paidAmount ELSE 0 END), 0)) "
          + "FROM Booking b "
          + "WHERE b.performanceId IN :performanceIds "
          + "GROUP BY b.performanceId "
          + "ORDER BY b.performanceId ASC")
  List<BookingPerformanceStatsRow> aggregateStatsByPerformanceIdIn(
      @Param("confirmed") BookingStatus confirmed,
      @Param("performanceIds") List<Long> performanceIds);

  /*
   * 일별 매출 집계 (#563 관리자 대시보드). 확정 시각 기준이며 호출자가 넘긴 반열린 구간 [from, toExclusive)만 센다.
   *
   * 경계를 반열린 구간으로 받는 이유: confirmed_at은 datetime이라 "to일 23:59:59.999999까지"를 값으로 표현하려
   * 하면 마이크로초 절삭에 기대게 된다. 다음 날 0시 미만으로 자르면 그 함정이 없다.
   *
   * cast(... as LocalDate)는 Hibernate가 MySQL의 DATE()로 번역한다. FUNCTION('DATE', ...)와 달리 반환
   * 타입이 HQL 수준에서 확정되어 생성자 표현식의 LocalDate 파라미터에 그대로 들어간다.
   *
   * 이 쿼리는 새 인덱스 없이도 기존 (booking_status, updated_at) 인덱스의 선두 컬럼을 탄다 —
   * 실측(로컬 MySQL, booking 3,000행 / CONFIRMED 1,000행, 30일 구간):
   *   Index lookup on b using idx_booking_status_updated_at (booking_status='CONFIRMED')
   *     (cost=26.1 rows=1000) -> Filter: confirmed_at 범위 (rows=111)
   * 즉 전건 3,000행이 아니라 CONFIRMED 1,000행만 훑는다. confirmed_at은 인덱스 두 번째 컬럼이 아니라
   * (updated_at이다) 범위 조건이 인덱스로 좁혀지지는 않지만, 상태 선별만으로 이 규모에서는 충분하다.
   */
  @Query(
      "SELECT new com.ticketrush.boundedcontext.booking.app.dto.response.BookingDailyRevenueRow("
          + "cast(b.confirmedAt as LocalDate), "
          + "COALESCE(SUM(b.paidAmount), 0)) "
          + "FROM Booking b "
          + "WHERE b.bookingStatus = :confirmed "
          + "AND b.confirmedAt >= :from AND b.confirmedAt < :toExclusive "
          + "GROUP BY cast(b.confirmedAt as LocalDate) "
          + "ORDER BY cast(b.confirmedAt as LocalDate) ASC")
  List<BookingDailyRevenueRow> aggregateDailyRevenue(
      @Param("confirmed") BookingStatus confirmed,
      @Param("from") LocalDateTime from,
      @Param("toExclusive") LocalDateTime toExclusive);

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
