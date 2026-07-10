package com.ticketrush.boundedcontext.booking.out.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
class BookingRepositoryTest {

  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 7, 10, 12, 0);

  @Autowired private BookingRepository bookingRepository;

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

  private Booking booking(String bookingNumber, BookingStatus status) {
    return Booking.builder()
        .userId(1L)
        .performanceId(2L)
        .seatId(3L)
        .bookingNumber(bookingNumber)
        .bookingStatus(status)
        .build();
  }
}
