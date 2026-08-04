package com.ticketrush.boundedcontext.booking.out.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingPerformanceStatsRow;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.global.jpa.config.JpaConfig;
import java.time.LocalDateTime;
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
        .setParameter("past", LocalDateTime.now().minusHours(1))
        .setParameter("id", stuck.getId())
        .executeUpdate();
    em.clear();

    // when: cutoff = 30분 전
    Page<Booking> found =
        bookingRepository.findByBookingStatusAndUpdatedAtBefore(
            BookingStatus.REFUNDING, LocalDateTime.now().minusMinutes(30), PageRequest.of(0, 10));

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
  @DisplayName("관리자 통계: 공연별로 전체·완료(CONFIRMED)·취소(CANCELED+REFUNDED) 수를 집계한다")
  void aggregateStatsByPerformance_CountsByStatusPerPerformance() {
    // given: 공연 10에 6개 상태를 하나씩, 공연 20에 CONFIRMED 2건
    bookingRepository.save(booking("BK-S1", BookingStatus.CONFIRMED, 10L));
    bookingRepository.save(booking("BK-S2", BookingStatus.CANCELED, 10L));
    bookingRepository.save(booking("BK-S3", BookingStatus.REFUNDED, 10L));
    bookingRepository.save(booking("BK-S4", BookingStatus.EXPIRED, 10L));
    bookingRepository.save(booking("BK-S5", BookingStatus.PENDING, 10L));
    bookingRepository.save(booking("BK-S6", BookingStatus.REFUNDING, 10L));
    bookingRepository.save(booking("BK-S7", BookingStatus.CONFIRMED, 20L));
    bookingRepository.save(booking("BK-S8", BookingStatus.CONFIRMED, 20L));

    // when
    List<BookingPerformanceStatsRow> rows =
        bookingRepository.aggregateStatsByPerformance(
            BookingStatus.CONFIRMED, BookingStatus.CANCELED, BookingStatus.REFUNDED);

    // then: performanceId 오름차순으로 고정된다
    assertThat(rows)
        .extracting(BookingPerformanceStatsRow::performanceId)
        .containsExactly(10L, 20L);

    BookingPerformanceStatsRow first = rows.get(0);
    assertThat(first.totalCount()).isEqualTo(6);
    assertThat(first.confirmedCount()).isEqualTo(1);
    // EXPIRED·PENDING·REFUNDING은 취소로 세지 않는다 — 넣으면 취소율이 부풀려진다.
    assertThat(first.canceledCount()).isEqualTo(2);

    BookingPerformanceStatsRow second = rows.get(1);
    assertThat(second.totalCount()).isEqualTo(2);
    assertThat(second.confirmedCount()).isEqualTo(2);
    assertThat(second.canceledCount()).isZero();
  }

  @Test
  @DisplayName("관리자 통계: 예매가 없으면 빈 목록을 반환한다")
  void aggregateStatsByPerformance_WhenNoBookings_ReturnsEmpty() {
    // when
    List<BookingPerformanceStatsRow> rows =
        bookingRepository.aggregateStatsByPerformance(
            BookingStatus.CONFIRMED, BookingStatus.CANCELED, BookingStatus.REFUNDED);

    // then
    assertThat(rows).isEmpty();
  }

  private Booking booking(String bookingNumber, BookingStatus status) {
    return booking(bookingNumber, status, 2L);
  }

  private Booking booking(String bookingNumber, BookingStatus status, Long performanceId) {
    return Booking.builder()
        .userId(1L)
        .performanceId(performanceId)
        .seatId(3L)
        .bookingNumber(bookingNumber)
        .bookingStatus(status)
        .build();
  }
}
