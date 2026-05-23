package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BookingGetMyBookingsUseCaseTest {

  @InjectMocks private BookingGetMyBookingsUseCase bookingGetMyBookingsUseCase;

  @Mock private BookingRepository bookingRepository;

  @Test
  @DisplayName("성공: 회원 ID와 상태로 예매 요약 목록을 조회한다")
  void execute_success() {
    // given
    Long userId = 1L;
    LocalDateTime confirmedAt = LocalDateTime.of(2026, 5, 22, 10, 30);
    Booking booking =
        Booking.builder()
            .userId(userId)
            .performanceId(2L)
            .seatId(3L)
            .bookingNumber("BOOK-1234")
            .bookingStatus(BookingStatus.PENDING)
            .build();
    ReflectionTestUtils.setField(booking, "id", 100L);
    booking.confirm(confirmedAt);

    given(
            bookingRepository.findByUserIdAndBookingStatusOrderByConfirmedAtDescCreatedAtDesc(
                userId, BookingStatus.CONFIRMED))
        .willReturn(List.of(booking));

    // when
    List<BookingSummaryResponse> result =
        bookingGetMyBookingsUseCase.execute(userId, BookingStatus.CONFIRMED);

    // then
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().bookingId()).isEqualTo(100L);
    assertThat(result.getFirst().bookingNumber()).isEqualTo("BOOK-1234");
    assertThat(result.getFirst().performanceId()).isEqualTo(2L);
    assertThat(result.getFirst().seatId()).isEqualTo(3L);
    assertThat(result.getFirst().confirmedAt()).isEqualTo(confirmedAt);
    verify(bookingRepository)
        .findByUserIdAndBookingStatusOrderByConfirmedAtDescCreatedAtDesc(
            userId, BookingStatus.CONFIRMED);
  }
}
