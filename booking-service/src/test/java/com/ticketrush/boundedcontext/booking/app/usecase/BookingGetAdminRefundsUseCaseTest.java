package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.domain.types.RefundProcessStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import java.util.List;
import java.util.Set;
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
class BookingGetAdminRefundsUseCaseTest {

  @Mock private BookingRepository bookingRepository;

  @InjectMocks private BookingGetAdminRefundsUseCase bookingGetAdminRefundsUseCase;

  @Test
  @DisplayName("성공: 필터가 없으면 환불 진행·완료·미해결 실패 통합 조회로 간다")
  void execute_without_filter_queries_all_refund_targets() {
    // given
    given(
            bookingRepository.findRefundTargets(
                BookingStatus.REFUNDING,
                BookingStatus.REFUNDED,
                BookingStatus.CONFIRMED,
                PageRequest.of(0, 10, Sort.by(Sort.Order.desc("id")))))
        .willReturn(new PageImpl<>(List.of()));

    // when
    Page<Booking> result =
        bookingGetAdminRefundsUseCase.execute(null, new OffsetPageRequest(0, 10));

    // then
    assertThat(result).isEmpty();
    verify(bookingRepository)
        .findRefundTargets(
            BookingStatus.REFUNDING,
            BookingStatus.REFUNDED,
            BookingStatus.CONFIRMED,
            PageRequest.of(0, 10, Sort.by(Sort.Order.desc("id"))));
    verifyNoMoreInteractions(bookingRepository);
  }

  @Test
  @DisplayName("성공: IN_PROGRESS는 REFUNDING만 조회한다")
  void execute_with_in_progress_queries_refunding_only() {
    // given
    given(bookingRepository.findByBookingStatusIn(any(), any(Pageable.class)))
        .willReturn(new PageImpl<>(List.of()));

    // when
    bookingGetAdminRefundsUseCase.execute(
        RefundProcessStatus.IN_PROGRESS, new OffsetPageRequest(0, 10));

    // then
    verify(bookingRepository)
        .findByBookingStatusIn(
            Set.of(BookingStatus.REFUNDING), PageRequest.of(0, 10, Sort.by(Sort.Order.desc("id"))));
    verifyNoMoreInteractions(bookingRepository);
  }

  @Test
  @DisplayName("성공: COMPLETED는 REFUNDED만 조회한다")
  void execute_with_completed_queries_refunded_only() {
    // given
    given(bookingRepository.findByBookingStatusIn(any(), any(Pageable.class)))
        .willReturn(new PageImpl<>(List.of()));

    // when
    bookingGetAdminRefundsUseCase.execute(
        RefundProcessStatus.COMPLETED, new OffsetPageRequest(0, 10));

    // then
    verify(bookingRepository)
        .findByBookingStatusIn(
            Set.of(BookingStatus.REFUNDED), PageRequest.of(0, 10, Sort.by(Sort.Order.desc("id"))));
    verifyNoMoreInteractions(bookingRepository);
  }

  @Test
  @DisplayName("성공: FAILED는 CONFIRMED + 실패 이력 조회로 간다 — 통합 쿼리를 태우지 않는다")
  void execute_with_failed_queries_confirmed_with_failure_history() {
    // given: 실패는 BookingStatus가 아니라 CONFIRMED + refundFailedAt 조합이라(ADR 0005)
    // 상태 IN 필터로는 정상 확정 예매와 구분되지 않는다.
    given(
            bookingRepository.findByBookingStatusAndRefundFailedAtIsNotNull(
                any(BookingStatus.class), any(Pageable.class)))
        .willReturn(new PageImpl<>(List.of()));

    // when
    bookingGetAdminRefundsUseCase.execute(RefundProcessStatus.FAILED, new OffsetPageRequest(0, 10));

    // then
    verify(bookingRepository)
        .findByBookingStatusAndRefundFailedAtIsNotNull(
            BookingStatus.CONFIRMED, PageRequest.of(0, 10, Sort.by(Sort.Order.desc("id"))));
    verifyNoMoreInteractions(bookingRepository);
  }

  @Test
  @DisplayName("성공: 페이지 요청과 id DESC 정렬이 네 경로에서 모두 같다")
  void execute_applies_same_paging_and_sort_for_every_filter() {
    // given: 필터마다 정렬이 갈리면 탭을 바꿀 때 같은 예매가 다른 페이지로 옮겨 다닌다.
    given(bookingRepository.findRefundTargets(any(), any(), any(), any(Pageable.class)))
        .willReturn(new PageImpl<>(List.of()));
    given(bookingRepository.findByBookingStatusIn(any(), any(Pageable.class)))
        .willReturn(new PageImpl<>(List.of()));
    given(
            bookingRepository.findByBookingStatusAndRefundFailedAtIsNotNull(
                any(BookingStatus.class), any(Pageable.class)))
        .willReturn(new PageImpl<>(List.of()));

    OffsetPageRequest pageRequest = new OffsetPageRequest(2, 20);

    // when
    bookingGetAdminRefundsUseCase.execute(null, pageRequest);
    bookingGetAdminRefundsUseCase.execute(RefundProcessStatus.IN_PROGRESS, pageRequest);
    bookingGetAdminRefundsUseCase.execute(RefundProcessStatus.COMPLETED, pageRequest);
    bookingGetAdminRefundsUseCase.execute(RefundProcessStatus.FAILED, pageRequest);

    // then
    PageRequest expected = PageRequest.of(2, 20, Sort.by(Sort.Order.desc("id")));
    ArgumentCaptor<Pageable> allTargets = ArgumentCaptor.forClass(Pageable.class);
    ArgumentCaptor<Pageable> byStatus = ArgumentCaptor.forClass(Pageable.class);
    ArgumentCaptor<Pageable> byFailure = ArgumentCaptor.forClass(Pageable.class);

    verify(bookingRepository).findRefundTargets(any(), any(), any(), allTargets.capture());
    verify(bookingRepository, org.mockito.Mockito.times(2))
        .findByBookingStatusIn(any(), byStatus.capture());
    verify(bookingRepository)
        .findByBookingStatusAndRefundFailedAtIsNotNull(
            any(BookingStatus.class), byFailure.capture());

    assertThat(allTargets.getValue()).isEqualTo(expected);
    assertThat(byStatus.getAllValues()).containsOnly(expected);
    assertThat(byFailure.getValue()).isEqualTo(expected);
  }
}
