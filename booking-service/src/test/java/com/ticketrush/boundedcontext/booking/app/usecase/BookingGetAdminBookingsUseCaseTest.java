package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class BookingGetAdminBookingsUseCaseTest {

  @InjectMocks private BookingGetAdminBookingsUseCase bookingGetAdminBookingsUseCase;

  @Mock private BookingRepository bookingRepository;

  private static final OffsetPageRequest PAGE_REQUEST = new OffsetPageRequest(1, 20);

  private static Booking booking(BookingStatus status) {
    return Booking.builder()
        .userId(10L)
        .performanceId(2L)
        .seatId(3L)
        .bookingNumber("BOOK-1234")
        .bookingStatus(status)
        .build();
  }

  @Test
  @DisplayName("성공: status가 없으면 상태 무관 전체를 조회한다 (파라미터 도입 전과 같은 동작)")
  void execute_without_status_queries_all() {
    // given
    Booking booking = booking(BookingStatus.CONFIRMED);
    given(bookingRepository.findAll(any(Pageable.class)))
        .willReturn(new PageImpl<>(List.of(booking)));

    // when
    Page<Booking> result = bookingGetAdminBookingsUseCase.execute(null, PAGE_REQUEST);

    // then
    assertThat(result.getContent()).containsExactly(booking);
    verify(bookingRepository, never()).findByBookingStatus(any(), any());

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(bookingRepository).findAll(captor.capture());
    assertThat(captor.getValue()).isEqualTo(PageRequest.of(1, 20, Sort.by(Sort.Order.desc("id"))));
  }

  @Test
  @DisplayName("성공: status를 주면 해당 상태만 조회하고 페이징·정렬 규약은 그대로다")
  void execute_with_status_filters_by_status() {
    // given
    Booking booking = booking(BookingStatus.REFUNDED);
    given(bookingRepository.findByBookingStatus(eq(BookingStatus.REFUNDED), any(Pageable.class)))
        .willReturn(new PageImpl<>(List.of(booking)));

    // when
    Page<Booking> result =
        bookingGetAdminBookingsUseCase.execute(BookingStatus.REFUNDED, PAGE_REQUEST);

    // then
    assertThat(result.getContent()).containsExactly(booking);
    verify(bookingRepository, never()).findAll(any(Pageable.class));

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(bookingRepository).findByBookingStatus(eq(BookingStatus.REFUNDED), captor.capture());
    assertThat(captor.getValue()).isEqualTo(PageRequest.of(1, 20, Sort.by(Sort.Order.desc("id"))));
  }
}
