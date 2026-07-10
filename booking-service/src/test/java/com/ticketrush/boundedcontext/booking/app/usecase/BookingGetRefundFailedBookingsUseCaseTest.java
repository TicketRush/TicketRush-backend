package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class BookingGetRefundFailedBookingsUseCaseTest {

  @InjectMocks private BookingGetRefundFailedBookingsUseCase bookingGetRefundFailedBookingsUseCase;

  @Mock private BookingRepository bookingRepository;

  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 7, 10, 12, 0);

  @Test
  @DisplayName("성공: CONFIRMED이면서 환불 실패 이력이 있는 예매만 조회한다")
  void execute_returns_only_unresolved_refund_failures() {
    // given
    Booking booking =
        Booking.builder()
            .userId(10L)
            .performanceId(2L)
            .seatId(3L)
            .bookingNumber("BOOK-1234")
            .bookingStatus(BookingStatus.REFUNDING)
            .build();
    booking.recordRefundFailure(FAILED_AT);

    ArgumentCaptor<BookingStatus> statusCaptor = ArgumentCaptor.forClass(BookingStatus.class);
    given(
            bookingRepository.findByBookingStatusAndRefundFailedAtIsNotNull(
                statusCaptor.capture(), any(Pageable.class)))
        .willReturn(new PageImpl<>(List.of(booking)));

    // when
    Page<BookingSummaryResponse> result =
        bookingGetRefundFailedBookingsUseCase.execute(new OffsetPageRequest(0, 10));

    // then: 환불 실패는 상태가 아니라 CONFIRMED + refundFailedAt 조합으로 표현된다 (#391)
    verify(bookingRepository)
        .findByBookingStatusAndRefundFailedAtIsNotNull(
            any(BookingStatus.class), any(Pageable.class));
    assertThat(statusCaptor.getValue()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().getFirst().refundFailedAt()).isEqualTo(FAILED_AT);
    assertThat(result.getContent().getFirst().bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
  }
}
