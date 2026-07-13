package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.policy.RefundingStuckPolicy;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BookingGetRefundingStuckBookingsUseCaseTest {

  private BookingGetRefundingStuckBookingsUseCase bookingGetRefundingStuckBookingsUseCase;

  @Mock private BookingRepository bookingRepository;

  private static final long STUCK_THRESHOLD_MINUTES = 30;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 13, 12, 0);

  @BeforeEach
  void setUp() {
    Clock fixedClock =
        Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    bookingGetRefundingStuckBookingsUseCase =
        new BookingGetRefundingStuckBookingsUseCase(
            bookingRepository, new RefundingStuckPolicy(fixedClock, STUCK_THRESHOLD_MINUTES));
  }

  @Test
  @DisplayName("성공: REFUNDING에서 임계 시간 이상 멈춘 예매를 임계 기준 시각(cutoff)으로 조회한다")
  void execute_queries_refunding_bookings_stuck_beyond_threshold() {
    // given: 종결 이벤트가 오지 않아 REFUNDING에 멈춘 예매
    LocalDateTime stuckSince = NOW.minusMinutes(STUCK_THRESHOLD_MINUTES + 10);
    Booking booking =
        Booking.builder()
            .userId(10L)
            .performanceId(2L)
            .seatId(3L)
            .bookingNumber("BOOK-1234")
            .bookingStatus(BookingStatus.REFUNDING)
            .build();
    ReflectionTestUtils.setField(booking, "updatedAt", stuckSince);

    ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    given(
            bookingRepository.findByBookingStatusAndUpdatedAtBefore(
                eq(BookingStatus.REFUNDING), cutoffCaptor.capture(), any(Pageable.class)))
        .willReturn(new PageImpl<>(List.of(booking)));

    // when
    Page<BookingSummaryResponse> result =
        bookingGetRefundingStuckBookingsUseCase.execute(new OffsetPageRequest(0, 10));

    // then: cutoff = 현재 시각 - 임계. updatedAt이 이보다 이전인 REFUNDING만 고착으로 잡힌다 (#397)
    assertThat(cutoffCaptor.getValue()).isEqualTo(NOW.minusMinutes(STUCK_THRESHOLD_MINUTES));
    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().getFirst().bookingStatus()).isEqualTo(BookingStatus.REFUNDING);
    assertThat(result.getContent().getFirst().updatedAt()).isEqualTo(stuckSince);
  }
}
