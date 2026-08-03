package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BookingGetMyBookingsUseCaseTest {

  @InjectMocks private BookingGetMyBookingsUseCase bookingGetMyBookingsUseCase;

  @Mock private BookingRepository bookingRepository;

  @Test
  @DisplayName("성공: PENDING 목록은 결제 마감 시각(createdAt + 5분)을 함께 내린다 (#559)")
  void execute_pending_includes_payment_deadline() {
    // given: 새로고침 후 타이머를 복원하려면 조회 응답에 마감 시각이 있어야 한다.
    // seat의 holdExpiredAt은 좌석 선점이 비동기라 이 값보다 늦으므로 쓰지 않는다.
    Long userId = 1L;
    LocalDateTime createdAt = LocalDateTime.of(2026, 8, 2, 10, 30);
    Booking booking =
        Booking.builder()
            .userId(userId)
            .performanceId(2L)
            .seatId(3L)
            .bookingNumber("X7B29-KLPW1")
            .bookingStatus(BookingStatus.PENDING)
            .build();
    ReflectionTestUtils.setField(booking, "id", 100L);
    ReflectionTestUtils.setField(booking, "createdAt", createdAt);

    given(
            bookingRepository.findByUserIdAndBookingStatus(
                userId,
                BookingStatus.PENDING,
                PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt")))))
        .willReturn(new PageImpl<>(List.of(booking), PageRequest.of(0, 10), 1));

    // when
    Page<BookingSummaryResponse> result =
        bookingGetMyBookingsUseCase.execute(
            userId, BookingStatus.PENDING, new OffsetPageRequest(0, 10));

    // then
    assertThat(result.getContent().getFirst().expiresAt())
        .isEqualTo(createdAt.plusMinutes(Booking.PAYMENT_WAIT_MINUTES));
  }

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
            bookingRepository.findByUserIdAndBookingStatus(
                userId,
                BookingStatus.CONFIRMED,
                PageRequest.of(
                    0, 10, Sort.by(Sort.Order.desc("confirmedAt"), Sort.Order.desc("createdAt")))))
        .willReturn(new PageImpl<>(List.of(booking), PageRequest.of(0, 10), 1));

    // when
    Page<BookingSummaryResponse> result =
        bookingGetMyBookingsUseCase.execute(
            userId, BookingStatus.CONFIRMED, new OffsetPageRequest(0, 10));

    // then
    assertThat(result).hasSize(1);
    assertThat(result.getContent().getFirst().bookingId()).isEqualTo(100L);
    assertThat(result.getContent().getFirst().bookingNumber()).isEqualTo("BOOK-1234");
    assertThat(result.getContent().getFirst().performanceId()).isEqualTo(2L);
    assertThat(result.getContent().getFirst().seatId()).isEqualTo(3L);
    assertThat(result.getContent().getFirst().confirmedAt()).isEqualTo(confirmedAt);
    verify(bookingRepository)
        .findByUserIdAndBookingStatus(
            userId,
            BookingStatus.CONFIRMED,
            PageRequest.of(
                0, 10, Sort.by(Sort.Order.desc("confirmedAt"), Sort.Order.desc("createdAt"))));
  }
}
