package com.ticketrush.boundedcontext.booking.out.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class BookingRepositoryTest {

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
}
