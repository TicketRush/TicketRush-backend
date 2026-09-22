package com.ticketrush.boundedcontext.booking.out.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingDailyRevenueRow;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPerformanceStatsRow;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingRefundStatsResponse;
import com.ticketrush.boundedcontext.booking.app.dto.response.BookingStatsCounts;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.domain.types.RefundProcessStatus;
import com.ticketrush.global.jpa.config.JpaConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * {@link JpaConfig}를 임포트해 {@code @EnableJpaAuditing}을 활성화한다 — 고착 조회 테스트가 {@code updatedAt}에 의존한다.
 */
@DataJpaTest
@Import(JpaConfig.class)
class BookingRepositoryTest {

  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 7, 10, 12, 0);

  @Autowired private BookingRepository bookingRepository;

  @Autowired private TestEntityManager em;

  @Test
  @DisplayName("bookingNumber로 예매 id를 조회한다")
  void findIdByBookingNumber_ReturnsId() {
    // given
    Booking booking =
        Booking.builder()
            .userId(1L)
            .performanceId(2L)
            .seatId(3L)
            .bookingNumber("BK-1")
            .bookingStatus(BookingStatus.PENDING)
            .build();
    Booking saved = bookingRepository.save(booking);

    // when
    Optional<Long> found = bookingRepository.findIdByBookingNumber("BK-1");

    // then
    assertThat(found).contains(saved.getId());
  }

  @Test
  @DisplayName("존재하지 않는 bookingNumber로 조회하면 빈 값을 반환한다")
  void findIdByBookingNumber_WhenNotExists_ReturnsEmpty() {
    // when
    Optional<Long> found = bookingRepository.findIdByBookingNumber("NON-EXISTENT");

    // then
    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("bookingNumber로 예매를 조회한다(소유자 검증 없이, 관리자용)")
  void findByBookingNumber_ReturnsBooking() {
    // given
    Booking saved = bookingRepository.save(booking("BK-2", BookingStatus.CONFIRMED));

    // when
    Optional<Booking> found = bookingRepository.findByBookingNumber("BK-2");

    // then
    assertThat(found).map(Booking::getId).contains(saved.getId());
  }

  @Test
  @DisplayName("관리자 복수 상태 목록: 상태가 치우쳐도 전역 id desc 연속 페이지에 누락·중복이 없다 (#674)")
  void findByBookingStatusIn_AppliesGlobalIdDescPagingAndCount() {
    // given: 오래된 REFUNDING 2건 뒤에 최신 PENDING 4건을 몰아 상태별 페이지 합치기의 오류를 드러낸다
    bookingRepository.save(booking("BK-R1", BookingStatus.REFUNDING));
    bookingRepository.save(booking("BK-R2", BookingStatus.REFUNDING));
    bookingRepository.save(booking("BK-P1", BookingStatus.PENDING));
    bookingRepository.save(booking("BK-P2", BookingStatus.PENDING));
    bookingRepository.save(booking("BK-P3", BookingStatus.PENDING));
    bookingRepository.save(booking("BK-P4", BookingStatus.PENDING));
    bookingRepository.save(booking("BK-CANCELED", BookingStatus.CANCELED));
    bookingRepository.save(booking("BK-EXPIRED", BookingStatus.EXPIRED));

    List<BookingStatus> statuses =
        List.of(BookingStatus.PENDING, BookingStatus.REFUNDING, BookingStatus.PENDING);
    Sort idDesc = Sort.by(Sort.Order.desc("id"));

    // when
    Page<Booking> first =
        bookingRepository.findByBookingStatusIn(statuses, PageRequest.of(0, 2, idDesc));
    Page<Booking> second =
        bookingRepository.findByBookingStatusIn(statuses, PageRequest.of(1, 2, idDesc));
    Page<Booking> last =
        bookingRepository.findByBookingStatusIn(statuses, PageRequest.of(2, 2, idDesc));
    Page<Booking> outOfRange =
        bookingRepository.findByBookingStatusIn(statuses, PageRequest.of(3, 2, idDesc));

    // then: 중복 status가 있어도 행과 count는 중복되지 않고, 메타데이터도 같은 필터 모집단을 쓴다
    assertThat(first.getContent())
        .extracting(Booking::getBookingNumber)
        .containsExactly("BK-P4", "BK-P3");
    assertThat(second.getContent())
        .extracting(Booking::getBookingNumber)
        .containsExactly("BK-P2", "BK-P1");
    assertThat(last.getContent())
        .extracting(Booking::getBookingNumber)
        .containsExactly("BK-R2", "BK-R1");
    assertThat(first.getTotalElements()).isEqualTo(6);
    assertThat(first.getTotalPages()).isEqualTo(3);
    assertThat(first.hasNext()).isTrue();
    assertThat(last.hasNext()).isFalse();
    assertThat(outOfRange.getContent()).isEmpty();
    assertThat(outOfRange.getTotalElements()).isEqualTo(6);
    assertThat(outOfRange.getTotalPages()).isEqualTo(3);
    assertThat(outOfRange.hasNext()).isFalse();
  }

  @Test
  @DisplayName("관리자 복수 상태 목록: 네 상태를 지정하면 CANCELED와 EXPIRED를 목록·count에서 제외한다 (#674)")
  void findByBookingStatusIn_ExcludesUnselectedStatusesFromContentAndCount() {
    // given
    bookingRepository.save(booking("BK-CONFIRMED", BookingStatus.CONFIRMED));
    bookingRepository.save(booking("BK-PENDING", BookingStatus.PENDING));
    bookingRepository.save(booking("BK-REFUNDING", BookingStatus.REFUNDING));
    bookingRepository.save(booking("BK-REFUNDED", BookingStatus.REFUNDED));
    bookingRepository.save(booking("BK-CANCELED", BookingStatus.CANCELED));
    bookingRepository.save(booking("BK-EXPIRED", BookingStatus.EXPIRED));

    // when
    Page<Booking> found =
        bookingRepository.findByBookingStatusIn(
            List.of(
                BookingStatus.CONFIRMED,
                BookingStatus.PENDING,
                BookingStatus.REFUNDING,
                BookingStatus.REFUNDED),
            PageRequest.of(0, 10, Sort.by(Sort.Order.desc("id"))));

    // then
    assertThat(found.getContent())
        .extracting(Booking::getBookingNumber)
        .containsExactly("BK-REFUNDED", "BK-REFUNDING", "BK-PENDING", "BK-CONFIRMED");
    assertThat(found.getTotalElements()).isEqualTo(4);
    assertThat(found.getTotalPages()).isEqualTo(1);
    assertThat(found.hasNext()).isFalse();
  }

  @Test
  @DisplayName("관리자 복수 상태 목록: 필터 결과가 없으면 빈 페이지 메타데이터를 반환한다 (#674)")
  void findByBookingStatusIn_WhenNoMatches_ReturnsEmptyPageMetadata() {
    // given
    bookingRepository.save(booking("BK-PENDING", BookingStatus.PENDING));

    // when
    Page<Booking> found =
        bookingRepository.findByBookingStatusIn(
            List.of(BookingStatus.REFUNDED), PageRequest.of(0, 10));

    // then
    assertThat(found.getContent()).isEmpty();
    assertThat(found.getTotalElements()).isZero();
    assertThat(found.getTotalPages()).isZero();
    assertThat(found.hasNext()).isFalse();
  }

  @Test
  @DisplayName("환불 실패 이력이 있는 CONFIRMED 예매만 조회한다")
  void findByBookingStatusAndRefundFailedAtIsNotNull_ReturnsOnlyUnresolvedFailures() {
    // given: 환불 실패로 복원된 예매, 실패 이력 없는 CONFIRMED 예매, 실패 후 끝내 환불된 예매
    Booking failed = booking("BK-FAILED", BookingStatus.REFUNDING);
    failed.recordRefundFailure(FAILED_AT);
    bookingRepository.save(failed);

    bookingRepository.save(booking("BK-CLEAN", BookingStatus.CONFIRMED));

    Booking resolved = booking("BK-RESOLVED", BookingStatus.REFUNDING);
    resolved.recordRefundFailure(FAILED_AT);
    resolved.markRefunded();
    bookingRepository.save(resolved);

    // when
    Page<Booking> found =
        bookingRepository.findByBookingStatusAndRefundFailedAtIsNotNull(
            BookingStatus.CONFIRMED, PageRequest.of(0, 10));

    // then: 실패 이력이 있어도 REFUNDED로 종결된 건은 더 이상 조치 대상이 아니다
    assertThat(found.getContent())
        .extracting(Booking::getBookingNumber)
        .containsExactly("BK-FAILED");
  }

  @Test
  @DisplayName("REFUNDING에서 cutoff 이전부터 멈춘 예매만 고착으로 조회한다 (#397)")
  void findByBookingStatusAndUpdatedAtBefore_ReturnsOnlyStuckRefunding() {
    // given: 고착 REFUNDING, 신선한 REFUNDING, 무관한 CONFIRMED
    bookingRepository.save(booking("BK-FRESH", BookingStatus.REFUNDING));
    bookingRepository.save(booking("BK-CONFIRMED", BookingStatus.CONFIRMED));
    Booking stuck = bookingRepository.save(booking("BK-STUCK", BookingStatus.REFUNDING));
    em.flush();

    // auditing이 관리하는 updatedAt은 저장 시각으로 고정되므로, 고착 시나리오는 벌크 UPDATE로 과거로 되돌린다
    em.getEntityManager()
        .createQuery("UPDATE Booking b SET b.updatedAt = :past WHERE b.id = :id")
        .setParameter("past", LocalDateTime.now(ZoneOffset.UTC).minusHours(1))
        .setParameter("id", stuck.getId())
        .executeUpdate();
    em.clear();

    // when: cutoff = 30분 전
    Page<Booking> found =
        bookingRepository.findByBookingStatusAndUpdatedAtBefore(
            BookingStatus.REFUNDING,
            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30),
            PageRequest.of(0, 10));

    // then: 방금 REFUNDING에 들어간 건과 다른 상태는 잡히지 않는다
    assertThat(found.getContent())
        .extracting(Booking::getBookingNumber)
        .containsExactly("BK-STUCK");
  }

  @Test
  @DisplayName("stale 버전으로 저장하면 낙관적 락 충돌이 발생한다 (#397)")
  void save_with_stale_version_throws_optimistic_locking_failure() {
    // given: 사용자 취소와 관리자 재환불이 같은 예매를 동시에 읽은 상황
    Booking stale = bookingRepository.save(booking("BK-VER", BookingStatus.CONFIRMED));
    em.flush();
    em.detach(stale);

    // 먼저 커밋한 쪽이 버전을 올린다
    Booking winner = bookingRepository.findById(stale.getId()).orElseThrow();
    winner.requestRefund();
    bookingRepository.saveAndFlush(winner);
    em.detach(winner);

    // when & then: 늦은 쪽(stale 버전)의 저장은 충돌로 실패한다 — 발행이 afterCommit이므로 이벤트 이중 발행도 없다
    stale.requestRefund();
    assertThatThrownBy(() -> bookingRepository.saveAndFlush(stale))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("관리자 통계: 상태별 건수와 확정 예매의 결제 금액 합을 한 번에 집계한다")
  void aggregateStats_CountsByStatusAndSumsRevenue() {
    // given: 6개 상태를 하나씩, 확정 예매 2건에 금액을 기록
    bookingRepository.save(confirmedBooking("BK-S1", 10_000L));
    bookingRepository.save(confirmedBooking("BK-S2", 25_000L));
    bookingRepository.save(booking("BK-S3", BookingStatus.CANCELED));
    bookingRepository.save(booking("BK-S4", BookingStatus.REFUNDED));
    bookingRepository.save(booking("BK-S5", BookingStatus.EXPIRED));
    bookingRepository.save(booking("BK-S6", BookingStatus.PENDING));
    bookingRepository.save(booking("BK-S7", BookingStatus.REFUNDING));

    // when
    BookingStatsCounts counts =
        bookingRepository.aggregateStats(
            BookingStatus.CONFIRMED, BookingStatus.CANCELED, BookingStatus.REFUNDED);

    // then
    assertThat(counts.totalCount()).isEqualTo(7);
    assertThat(counts.confirmedCount()).isEqualTo(2);
    // EXPIRED·PENDING·REFUNDING은 취소로 세지 않는다 — 넣으면 취소율이 부풀려진다.
    assertThat(counts.canceledCount()).isEqualTo(2);
    assertThat(counts.confirmedRevenue()).isEqualTo(35_000L);
    assertThat(counts.amountMissingCount()).isZero();
  }

  @Test
  @DisplayName("관리자 통계: 환불된 예매의 금액은 매출에서 빠진다")
  void aggregateStats_ExcludesRefundedFromRevenue() {
    // given: 확정 1건(금액 있음)과, 확정을 거쳐 환불된 1건(금액이 남아 있음)
    bookingRepository.save(confirmedBooking("BK-R1", 10_000L));
    Booking refunded = confirmedBooking("BK-R2", 99_000L);
    refunded.markRefunded();
    bookingRepository.save(refunded);

    // when
    BookingStatsCounts counts =
        bookingRepository.aggregateStats(
            BookingStatus.CONFIRMED, BookingStatus.CANCELED, BookingStatus.REFUNDED);

    // then: 돈이 나간 예매는 매출이 아니다
    assertThat(counts.confirmedRevenue()).isEqualTo(10_000L);
    assertThat(counts.canceledCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("관리자 통계: 결제 금액이 비어 있는 확정 예매를 따로 세어 축소된 매출을 드러낸다")
  void aggregateStats_ReportsMissingAmounts() {
    // given: 백필되지 않은 과거 확정 예매(금액 null)가 섞여 있다
    bookingRepository.save(confirmedBooking("BK-M1", 10_000L));
    bookingRepository.save(confirmedBooking("BK-M2", null));

    // when
    BookingStatsCounts counts =
        bookingRepository.aggregateStats(
            BookingStatus.CONFIRMED, BookingStatus.CANCELED, BookingStatus.REFUNDED);

    // then: SUM은 null을 조용히 건너뛰므로, 그 사실을 별도 카운트로 노출해야 축소를 알아챌 수 있다
    assertThat(counts.confirmedCount()).isEqualTo(2);
    assertThat(counts.confirmedRevenue()).isEqualTo(10_000L);
    assertThat(counts.amountMissingCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("관리자 통계: 예매가 없으면 모든 지표가 0이다")
  void aggregateStats_WhenNoBookings_ReturnsZeros() {
    // when
    BookingStatsCounts counts =
        bookingRepository.aggregateStats(
            BookingStatus.CONFIRMED, BookingStatus.CANCELED, BookingStatus.REFUNDED);

    // then
    assertThat(counts.totalCount()).isZero();
    assertThat(counts.confirmedCount()).isZero();
    assertThat(counts.canceledCount()).isZero();
    assertThat(counts.confirmedRevenue()).isZero();
    assertThat(counts.amountMissingCount()).isZero();
  }

  @Test
  @DisplayName("공연별 집계: 확정 예매만 공연 단위로 건수와 매출을 묶는다")
  void aggregateStatsByPerformance_GroupsConfirmedOnly() {
    // given: 공연 100에 확정 2건(+취소 1건), 공연 200에 확정 1건
    bookingRepository.save(confirmedBooking("BK-P1", 100L, 10_000L));
    bookingRepository.save(confirmedBooking("BK-P2", 100L, 25_000L));
    bookingRepository.save(performanceBooking("BK-P3", 100L, BookingStatus.CANCELED));
    bookingRepository.save(confirmedBooking("BK-P4", 200L, 7_000L));

    // when
    List<BookingPerformanceStatsRow> rows =
        bookingRepository.aggregateStatsByPerformance(BookingStatus.CONFIRMED);

    // then: 취소 건은 매출·건수 어디에도 잡히지 않지만 그 공연 자체는 행으로 남는다
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).performanceId()).isEqualTo(100L);
    assertThat(rows.get(0).confirmedCount()).isEqualTo(2);
    assertThat(rows.get(0).confirmedRevenue()).isEqualTo(35_000L);
    assertThat(rows.get(1).performanceId()).isEqualTo(200L);
    assertThat(rows.get(1).confirmedRevenue()).isEqualTo(7_000L);
  }

  @Test
  @DisplayName("공연별 집계: 공연 ID를 주면 그 공연만 집계하고 세는 규칙은 전건 집계와 같다 (#590)")
  void aggregateStatsByPerformanceIdIn_NarrowsToGivenIds() {
    // given: 공연 100에 확정 2건(+취소 1건), 공연 200에 확정 1건
    bookingRepository.save(confirmedBooking("BK-P1", 100L, 10_000L));
    bookingRepository.save(confirmedBooking("BK-P2", 100L, 25_000L));
    bookingRepository.save(performanceBooking("BK-P3", 100L, BookingStatus.CANCELED));
    bookingRepository.save(confirmedBooking("BK-P4", 200L, 7_000L));

    // when: 같은 픽스처에 두 경로를 모두 돌린다
    List<BookingPerformanceStatsRow> filtered =
        bookingRepository.aggregateStatsByPerformanceIdIn(BookingStatus.CONFIRMED, List.of(100L));
    List<BookingPerformanceStatsRow> all =
        bookingRepository.aggregateStatsByPerformance(BookingStatus.CONFIRMED);

    // then: 두 경로를 직접 맞대 본다. 기대값만 하드코딩하면 나중에 전건 쪽 CASE-WHEN만 고쳐도 두 쿼리가
    // 갈린 채 테스트가 모두 통과한다 — 이 이슈가 막으려는 회귀가 바로 그것이다.
    assertThat(filtered).hasSize(1);
    assertThat(filtered.getFirst())
        .isEqualTo(all.stream().filter(row -> row.performanceId().equals(100L)).findFirst().get());

    assertThat(filtered.getFirst().confirmedCount()).isEqualTo(2);
    assertThat(filtered.getFirst().confirmedRevenue()).isEqualTo(35_000L);
  }

  @Test
  @DisplayName("공연별 집계: 예매가 없는 공연 ID를 주면 그 공연은 행으로 나오지 않는다")
  void aggregateStatsByPerformanceIdIn_WhenNoBookings_OmitsPerformance() {
    // given
    bookingRepository.save(confirmedBooking("BK-P1", 100L, 10_000L));

    // when: GROUP BY는 행이 있는 그룹만 만든다 — 호출자는 이를 매출 0이 아니라 '모름'으로 다뤄야 한다
    List<BookingPerformanceStatsRow> rows =
        bookingRepository.aggregateStatsByPerformanceIdIn(
            BookingStatus.CONFIRMED, List.of(100L, 999L));

    // then
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().performanceId()).isEqualTo(100L);
  }

  @Test
  @DisplayName("공연별 집계: 확정 예매가 하나도 없는 공연도 매출 0으로 내려간다(SUM의 NULL 방어)")
  void aggregateStatsByPerformance_WhenNoConfirmed_ReturnsZeroNotNull() {
    // given: 취소 예매만 있는 공연
    bookingRepository.save(performanceBooking("BK-Z1", 300L, BookingStatus.CANCELED));

    // when
    List<BookingPerformanceStatsRow> rows =
        bookingRepository.aggregateStatsByPerformance(BookingStatus.CONFIRMED);

    // then: COALESCE가 없으면 record의 원시 long에 NULL이 들어가 NPE가 난다
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).confirmedCount()).isZero();
    assertThat(rows.get(0).confirmedRevenue()).isZero();
  }

  @Test
  @DisplayName("일별 매출: 확정일로 묶고 요청 구간 밖은 제외한다")
  void aggregateDailyRevenue_GroupsByConfirmedDate() {
    // given: 5/21 1건, 5/22 2건, 구간 밖(5/23) 1건
    bookingRepository.save(
        confirmedBookingAt("BK-D1", LocalDateTime.of(2026, 5, 21, 9, 0), 1_000L));
    bookingRepository.save(
        confirmedBookingAt("BK-D2", LocalDateTime.of(2026, 5, 22, 9, 0), 2_000L));
    bookingRepository.save(
        confirmedBookingAt("BK-D3", LocalDateTime.of(2026, 5, 22, 23, 59, 59), 3_000L));
    bookingRepository.save(
        confirmedBookingAt("BK-D4", LocalDateTime.of(2026, 5, 23, 0, 0), 9_000L));

    // when: [5/21 00:00, 5/23 00:00) — 종료일 5/22의 다음 날 0시 미만
    List<BookingDailyRevenueRow> rows =
        bookingRepository.aggregateDailyRevenue(
            BookingStatus.CONFIRMED,
            LocalDateTime.of(2026, 5, 21, 0, 0),
            LocalDateTime.of(2026, 5, 23, 0, 0));

    // then: 반열린 구간이라 5/23 0시 정각 건은 빠지고, 5/22 23:59:59 건은 들어온다
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).date()).isEqualTo(LocalDate.of(2026, 5, 21));
    assertThat(rows.get(0).revenue()).isEqualTo(1_000L);
    assertThat(rows.get(1).date()).isEqualTo(LocalDate.of(2026, 5, 22));
    assertThat(rows.get(1).revenue()).isEqualTo(5_000L);
  }

  @Test
  @DisplayName("일별 매출: 확정되지 않은 예매는 세지 않는다")
  void aggregateDailyRevenue_ExcludesNonConfirmed() {
    // given: 확정을 거쳐 환불된 예매(confirmedAt과 금액이 남아 있다)
    Booking refunded = confirmedBookingAt("BK-DR", LocalDateTime.of(2026, 5, 22, 9, 0), 50_000L);
    refunded.markRefunded();
    bookingRepository.save(refunded);

    // when
    List<BookingDailyRevenueRow> rows =
        bookingRepository.aggregateDailyRevenue(
            BookingStatus.CONFIRMED,
            LocalDateTime.of(2026, 5, 1, 0, 0),
            LocalDateTime.of(2026, 6, 1, 0, 0));

    // then: 돈이 나간 예매라 매출 추이에 남으면 안 된다
    assertThat(rows).isEmpty();
  }

  @Test
  @DisplayName("환불 통합 조회는 진행·완료·미해결 실패만 담고 그 외 예매는 제외한다 (#675)")
  void findRefundTargets_ReturnsThreeRefundKindsAndExcludesOthers() {
    // given: 환불 대상 3종과, 환불이 아닌 4종
    bookingRepository.save(booking("BK-REFUNDING", BookingStatus.REFUNDING));

    Booking refunded = booking("BK-REFUNDED", BookingStatus.REFUNDING);
    refunded.markRefunded();
    bookingRepository.save(refunded);

    Booking failed = booking("BK-FAILED", BookingStatus.REFUNDING);
    failed.recordRefundFailure(FAILED_AT);
    bookingRepository.save(failed);

    bookingRepository.save(booking("BK-CLEAN", BookingStatus.CONFIRMED));
    bookingRepository.save(booking("BK-PENDING", BookingStatus.PENDING));
    bookingRepository.save(booking("BK-CANCELED", BookingStatus.CANCELED));
    bookingRepository.save(booking("BK-EXPIRED", BookingStatus.EXPIRED));

    // when
    Page<Booking> found =
        bookingRepository.findRefundTargets(
            BookingStatus.REFUNDING,
            BookingStatus.REFUNDED,
            BookingStatus.CONFIRMED,
            PageRequest.of(0, 10, Sort.by(Sort.Order.desc("id"))));

    // then: 실패 이력이 없는 CONFIRMED가 섞이면 정상 확정 예매가 환불 화면에 쏟아진다.
    // 결제 전 취소(CANCELED)와 미결제 만료(EXPIRED)는 애초에 환불이 아니다.
    assertThat(found.getContent())
        .extracting(Booking::getBookingNumber)
        .containsExactlyInAnyOrder("BK-REFUNDING", "BK-REFUNDED", "BK-FAILED");
    assertThat(found.getTotalElements()).isEqualTo(3);
  }

  @Test
  @DisplayName("실패 이력이 남은 재시도 건도 한 행으로만 조회된다 (#675)")
  void findRefundTargets_CountsRetriedBookingOnce() {
    // given: refundFailedAt은 재환불 시 지워지지 않는다(ADR 0005). 실패 조건을 상태와 독립으로 걸면
    // 재시도 중·완료 건이 실패 분기에도 걸려 같은 예매가 목록에 두 번 나온다.
    Booking retrying = booking("BK-RETRYING", BookingStatus.REFUNDING);
    retrying.recordRefundFailure(FAILED_AT);
    retrying.requestRefund();
    bookingRepository.save(retrying);

    Booking resolved = booking("BK-RESOLVED", BookingStatus.REFUNDING);
    resolved.recordRefundFailure(FAILED_AT);
    resolved.markRefunded();
    bookingRepository.save(resolved);

    // when
    Page<Booking> found =
        bookingRepository.findRefundTargets(
            BookingStatus.REFUNDING,
            BookingStatus.REFUNDED,
            BookingStatus.CONFIRMED,
            PageRequest.of(0, 10, Sort.by(Sort.Order.desc("id"))));

    // then: 둘 다 실패 시각을 들고 있지만 행은 각각 하나뿐이다
    assertThat(found.getContent())
        .extracting(Booking::getBookingNumber)
        .containsExactlyInAnyOrder("BK-RETRYING", "BK-RESOLVED");
    assertThat(found.getTotalElements()).isEqualTo(2);
    assertThat(found.getContent())
        .allSatisfy(booking -> assertThat(booking.getRefundFailedAt()).isEqualTo(FAILED_AT));
  }

  @Test
  @DisplayName("환불 통합 조회는 전역 id DESC로 페이징하고 count가 같은 모집단을 본다 (#675)")
  void findRefundTargets_AppliesGlobalIdDescPagingAndCount() {
    // given: 종류가 치우쳐 저장된다 — 상태별로 따로 조회해 합치면 뒷페이지가 통째로 누락되는 배치다
    bookingRepository.save(booking("BK-01", BookingStatus.REFUNDING));
    bookingRepository.save(booking("BK-02", BookingStatus.REFUNDING));
    bookingRepository.save(booking("BK-03", BookingStatus.CONFIRMED)); // 환불 아님
    Booking failed = booking("BK-04", BookingStatus.REFUNDING);
    failed.recordRefundFailure(FAILED_AT);
    bookingRepository.save(failed);
    Booking refunded = booking("BK-05", BookingStatus.REFUNDING);
    refunded.markRefunded();
    bookingRepository.save(refunded);

    Sort idDesc = Sort.by(Sort.Order.desc("id"));

    // when
    Page<Booking> first =
        bookingRepository.findRefundTargets(
            BookingStatus.REFUNDING,
            BookingStatus.REFUNDED,
            BookingStatus.CONFIRMED,
            PageRequest.of(0, 2, idDesc));
    Page<Booking> second =
        bookingRepository.findRefundTargets(
            BookingStatus.REFUNDING,
            BookingStatus.REFUNDED,
            BookingStatus.CONFIRMED,
            PageRequest.of(1, 2, idDesc));

    // then: 연속 페이지에 누락·중복이 없고 메타데이터가 필터 결과와 일치한다
    assertThat(first.getContent())
        .extracting(Booking::getBookingNumber)
        .containsExactly("BK-05", "BK-04");
    assertThat(second.getContent())
        .extracting(Booking::getBookingNumber)
        .containsExactly("BK-02", "BK-01");
    assertThat(first.getTotalElements()).isEqualTo(4);
    assertThat(first.getTotalPages()).isEqualTo(2);
    assertThat(second.hasNext()).isFalse();
  }

  @Test
  @DisplayName("환불 대상이 없으면 빈 페이지 메타데이터를 낸다 (#675)")
  void findRefundTargets_WhenNoMatches_ReturnsEmptyPageMetadata() {
    // given: 환불과 무관한 예매만 있다
    bookingRepository.save(booking("BK-CLEAN", BookingStatus.CONFIRMED));
    bookingRepository.save(booking("BK-EXPIRED", BookingStatus.EXPIRED));

    // when
    Page<Booking> found =
        bookingRepository.findRefundTargets(
            BookingStatus.REFUNDING,
            BookingStatus.REFUNDED,
            BookingStatus.CONFIRMED,
            PageRequest.of(0, 10, Sort.by(Sort.Order.desc("id"))));

    // then
    assertThat(found.getContent()).isEmpty();
    assertThat(found.getTotalElements()).isZero();
    assertThat(found.getTotalPages()).isZero();
    assertThat(found.hasNext()).isFalse();
  }

  @Test
  @DisplayName("환불 통계는 목록과 같은 모집단을 세고 완료만 따로 센다 (#675)")
  void aggregateRefundStats_CountsPopulationAndCompleted() {
    // given: 진행 1 + 완료 2 + 미해결 실패 1 = 전체 4. 환불이 아닌 3종은 세지 않는다.
    bookingRepository.save(booking("BK-REFUNDING", BookingStatus.REFUNDING));

    Booking refundedOne = booking("BK-REFUNDED-1", BookingStatus.REFUNDING);
    refundedOne.markRefunded();
    bookingRepository.save(refundedOne);

    Booking refundedTwo = booking("BK-REFUNDED-2", BookingStatus.REFUNDING);
    refundedTwo.recordRefundFailure(FAILED_AT);
    refundedTwo.markRefunded();
    bookingRepository.save(refundedTwo);

    Booking failed = booking("BK-FAILED", BookingStatus.REFUNDING);
    failed.recordRefundFailure(FAILED_AT);
    bookingRepository.save(failed);

    bookingRepository.save(booking("BK-CLEAN", BookingStatus.CONFIRMED));
    bookingRepository.save(booking("BK-CANCELED", BookingStatus.CANCELED));
    bookingRepository.save(booking("BK-EXPIRED", BookingStatus.EXPIRED));

    // when
    BookingRefundStatsResponse stats =
        bookingRepository.aggregateRefundStats(
            BookingStatus.REFUNDING, BookingStatus.REFUNDED, BookingStatus.CONFIRMED);

    // then: CANCELED를 환불로 세면 예매 통계의 canceled_bookings와 같은 값이 되어 카드가 거짓말을 한다
    assertThat(stats.totalRefunds()).isEqualTo(4);
    assertThat(stats.completedRefunds()).isEqualTo(2);
  }

  @Test
  @DisplayName("환불 통계의 전체 건수와 목록의 전체 건수가 같은 모집단을 센다 (#675)")
  void aggregateRefundStats_AgreesWithRefundTargetsTotal() {
    // given: 두 쿼리가 술어를 각자 적어 두고 있어, 한쪽만 고치면 카드와 total_elements가 조용히 갈린다.
    // 그 드리프트를 같은 데이터로 직접 맞대어 잠근다. 환불 3종 + 환불 아님 3종을 섞는다.
    bookingRepository.save(booking("BK-REFUNDING", BookingStatus.REFUNDING));

    Booking refunded = booking("BK-REFUNDED", BookingStatus.REFUNDING);
    refunded.markRefunded();
    bookingRepository.save(refunded);

    Booking failed = booking("BK-FAILED", BookingStatus.REFUNDING);
    failed.recordRefundFailure(FAILED_AT);
    bookingRepository.save(failed);

    bookingRepository.save(booking("BK-CLEAN", BookingStatus.CONFIRMED));
    bookingRepository.save(booking("BK-CANCELED", BookingStatus.CANCELED));
    bookingRepository.save(booking("BK-EXPIRED", BookingStatus.EXPIRED));

    // when
    Page<Booking> list =
        bookingRepository.findRefundTargets(
            BookingStatus.REFUNDING,
            BookingStatus.REFUNDED,
            BookingStatus.CONFIRMED,
            PageRequest.of(0, 2, Sort.by(Sort.Order.desc("id"))));
    BookingRefundStatsResponse stats =
        bookingRepository.aggregateRefundStats(
            BookingStatus.REFUNDING, BookingStatus.REFUNDED, BookingStatus.CONFIRMED);

    // then: 페이지 크기를 모집단보다 작게 잡아도 전체 건수는 카드와 같아야 한다
    assertThat(list.getContent()).hasSize(2);
    assertThat(stats.totalRefunds()).isEqualTo(list.getTotalElements());
    assertThat(stats.totalRefunds()).isEqualTo(3);
  }

  @Test
  @DisplayName("환불 통합 조회가 반환한 모든 행은 환불 처리 상태로 파생된다 (#675)")
  void findRefundTargets_EveryRowIsClassifiable() {
    // given: 조회 조건(JPQL)과 파생 규칙(RefundProcessStatus.from)이 서로 다른 파일에 적혀 있다.
    // 갈리면 분류에 실패한 행 하나가 페이지 전체를 500으로 만드는데, 그 화면은 CS가 장애 중
    // 미해결 환불을 찾는 복구 도구다. 런타임이 아니라 여기서 먼저 빨개지게 잠근다.
    bookingRepository.save(booking("BK-REFUNDING", BookingStatus.REFUNDING));

    Booking refunded = booking("BK-REFUNDED", BookingStatus.REFUNDING);
    refunded.markRefunded();
    bookingRepository.save(refunded);

    Booking failed = booking("BK-FAILED", BookingStatus.REFUNDING);
    failed.recordRefundFailure(FAILED_AT);
    bookingRepository.save(failed);

    Booking retrying = booking("BK-RETRYING", BookingStatus.REFUNDING);
    retrying.recordRefundFailure(FAILED_AT);
    retrying.requestRefund();
    bookingRepository.save(retrying);

    bookingRepository.save(booking("BK-CLEAN", BookingStatus.CONFIRMED));
    bookingRepository.save(booking("BK-CANCELED", BookingStatus.CANCELED));

    // when
    Page<Booking> found =
        bookingRepository.findRefundTargets(
            BookingStatus.REFUNDING,
            BookingStatus.REFUNDED,
            BookingStatus.CONFIRMED,
            PageRequest.of(0, 50, Sort.by(Sort.Order.desc("id"))));

    // then: 한 행이라도 파생에 실패하면 여기서 IllegalStateException으로 터진다
    assertThat(found.getContent())
        .isNotEmpty()
        .allSatisfy(
            b ->
                assertThat(RefundProcessStatus.from(b.getBookingStatus(), b.getRefundFailedAt()))
                    .isNotNull());
  }

  @Test
  @DisplayName("진행 중 조회는 정상 진행 건과 고착 건을 모두 담는다 (#675)")
  void findRefundTargets_IncludesBothFreshAndStuckRefunding() {
    // given: 고착 조회(#397)는 임계 30분을 넘긴 건만 본다. 통합 목록의 진행 중이 그 조회로 대체되면
    // 방금 환불을 건 예매가 화면에서 사라진다 — 이슈가 명시적으로 금지한 구현이다.
    bookingRepository.save(booking("BK-FRESH", BookingStatus.REFUNDING));
    Booking stuck = bookingRepository.save(booking("BK-STUCK", BookingStatus.REFUNDING));
    em.flush();

    // auditing이 updatedAt을 저장 시각으로 고정하므로 고착 시나리오는 벌크 UPDATE로 과거로 되돌린다
    em.getEntityManager()
        .createQuery("UPDATE Booking b SET b.updatedAt = :past WHERE b.id = :id")
        .setParameter("past", LocalDateTime.now(ZoneOffset.UTC).minusHours(1))
        .setParameter("id", stuck.getId())
        .executeUpdate();
    em.clear();

    // when
    Page<Booking> found =
        bookingRepository.findRefundTargets(
            BookingStatus.REFUNDING,
            BookingStatus.REFUNDED,
            BookingStatus.CONFIRMED,
            PageRequest.of(0, 10, Sort.by(Sort.Order.desc("id"))));

    // then: updatedAt은 이 조회의 조건이 아니다
    assertThat(found.getContent())
        .extracting(Booking::getBookingNumber)
        .containsExactlyInAnyOrder("BK-FRESH", "BK-STUCK");
  }

  @Test
  @DisplayName("예매가 하나도 없어도 환불 통계는 0을 낸다 (#675)")
  void aggregateRefundStats_WhenNoBookings_ReturnsZeros() {
    // given: SUM은 대상 행이 없으면 0이 아니라 NULL이라, COALESCE가 없으면 record의 long에서 NPE가 난다

    // when
    BookingRefundStatsResponse stats =
        bookingRepository.aggregateRefundStats(
            BookingStatus.REFUNDING, BookingStatus.REFUNDED, BookingStatus.CONFIRMED);

    // then
    assertThat(stats.totalRefunds()).isZero();
    assertThat(stats.completedRefunds()).isZero();
  }

  private Booking booking(String bookingNumber, BookingStatus status) {
    return Booking.builder()
        .userId(1L)
        .performanceId(2L)
        .seatId(3L)
        .bookingNumber(bookingNumber)
        .bookingStatus(status)
        .build();
  }

  /** 결제 완료 경로를 그대로 태워 확정한다 — paidAmount는 confirm()으로만 채워진다. */
  private Booking confirmedBooking(String bookingNumber, Long paidAmount) {
    Booking booking = booking(bookingNumber, BookingStatus.PENDING);
    booking.confirm(LocalDateTime.of(2026, 5, 22, 10, 30), paidAmount);
    return booking;
  }

  private Booking confirmedBooking(String bookingNumber, Long performanceId, Long paidAmount) {
    Booking booking = performanceBooking(bookingNumber, performanceId, BookingStatus.PENDING);
    booking.confirm(LocalDateTime.of(2026, 5, 22, 10, 30), paidAmount);
    return booking;
  }

  private Booking performanceBooking(
      String bookingNumber, Long performanceId, BookingStatus status) {
    return Booking.builder()
        .userId(1L)
        .performanceId(performanceId)
        .seatId(3L)
        .bookingNumber(bookingNumber)
        .bookingStatus(status)
        .build();
  }

  private Booking confirmedBookingAt(
      String bookingNumber, LocalDateTime confirmedAt, Long paidAmount) {
    Booking booking = booking(bookingNumber, BookingStatus.PENDING);
    booking.confirm(confirmedAt, paidAmount);
    return booking;
  }
}
