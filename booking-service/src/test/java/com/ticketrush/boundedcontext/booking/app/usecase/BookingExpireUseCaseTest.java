package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingExpireUseCaseTest {

  private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
  private static final Instant NOW = Instant.parse("2026-05-27T06:00:00Z");

  @InjectMocks private BookingExpireUseCase bookingExpireUseCase;

  @Mock private BookingRepository bookingRepository;

  @Mock private Clock clock;

  @Test
  @DisplayName("성공: 생성 후 5분이 지난 PENDING 예매를 EXPIRED로 전이한다")
  void execute_success() {
    // given
    Booking booking =
        Booking.builder()
            .userId(1L)
            .performanceId(2L)
            .seatId(3L)
            .bookingNumber("BOOK-1234")
            .bookingStatus(BookingStatus.PENDING)
            .build();
    given(clock.instant()).willReturn(NOW);
    given(clock.getZone()).willReturn(ZONE_ID);
    given(
            bookingRepository.findTop100ByBookingStatusAndCreatedAtLessThanEqual(
                BookingStatus.PENDING, LocalDateTime.of(2026, 5, 27, 14, 55)))
        .willReturn(List.of(booking));

    // when
    int result = bookingExpireUseCase.execute();

    // then
    assertThat(result).isEqualTo(1);
    assertThat(booking.getBookingStatus()).isEqualTo(BookingStatus.EXPIRED);
  }

  @Test
  @DisplayName("성공: 현재 시각 기준 5분 전을 만료 조회 기준으로 사용한다")
  void execute_uses_cutoff_from_current_time() {
    // given
    given(clock.instant()).willReturn(NOW);
    given(clock.getZone()).willReturn(ZONE_ID);
    given(
            bookingRepository.findTop100ByBookingStatusAndCreatedAtLessThanEqual(
                BookingStatus.PENDING, LocalDateTime.of(2026, 5, 27, 14, 55)))
        .willReturn(List.of());

    // when
    bookingExpireUseCase.execute();

    // then
    ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(bookingRepository)
        .findTop100ByBookingStatusAndCreatedAtLessThanEqual(
            eq(BookingStatus.PENDING), cutoffCaptor.capture());
    assertThat(cutoffCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 5, 27, 14, 55));
  }

  @Test
  @DisplayName("성공: 조회 결과가 비어 있으면 전이 없이 0을 반환한다")
  void execute_returns_zero_when_no_expired_pending_booking() {
    // given
    given(clock.instant()).willReturn(NOW);
    given(clock.getZone()).willReturn(ZONE_ID);
    given(
            bookingRepository.findTop100ByBookingStatusAndCreatedAtLessThanEqual(
                BookingStatus.PENDING, LocalDateTime.of(2026, 5, 27, 14, 55)))
        .willReturn(List.of());

    // when
    int result = bookingExpireUseCase.execute();

    // then
    assertThat(result).isZero();
  }

  @Test
  @DisplayName("성공: 만료 대상이 100건 이상이면 같은 실행에서 다음 배치를 이어서 처리한다")
  void execute_processes_next_batch_when_first_batch_is_full() {
    // given
    List<Booking> firstBatch = createPendingBookings(100);
    List<Booking> secondBatch = createPendingBookings(3);
    given(clock.instant()).willReturn(NOW);
    given(clock.getZone()).willReturn(ZONE_ID);
    given(
            bookingRepository.findTop100ByBookingStatusAndCreatedAtLessThanEqual(
                BookingStatus.PENDING, LocalDateTime.of(2026, 5, 27, 14, 55)))
        .willReturn(firstBatch, secondBatch);

    // when
    int result = bookingExpireUseCase.execute();

    // then
    assertThat(result).isEqualTo(103);
    assertThat(firstBatch)
        .extracting(Booking::getBookingStatus)
        .containsOnly(BookingStatus.EXPIRED);
    assertThat(secondBatch)
        .extracting(Booking::getBookingStatus)
        .containsOnly(BookingStatus.EXPIRED);
  }

  private List<Booking> createPendingBookings(int count) {
    List<Booking> bookings = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      bookings.add(
          Booking.builder()
              .userId(1L)
              .performanceId(2L)
              .seatId((long) i)
              .bookingNumber("BOOK-" + i)
              .bookingStatus(BookingStatus.PENDING)
              .build());
    }
    return bookings;
  }
}
