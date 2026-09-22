package com.ticketrush.boundedcontext.booking.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingRefundStatsResponse;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookingGetAdminRefundStatsUseCaseTest {

  @Mock private BookingRepository bookingRepository;

  @InjectMocks private BookingGetAdminRefundStatsUseCase bookingGetAdminRefundStatsUseCase;

  @Test
  @DisplayName("성공: 환불 대상 세 상태를 집계 쿼리에 그대로 넘긴다")
  void execute_passes_refund_population_statuses() {
    // given: 목록(findRefundTargets)과 같은 상태 집합이어야 카드와 total_elements가 어긋나지 않는다.
    // CANCELED가 섞이면 결제 전 취소가 환불로 세어진다.
    given(
            bookingRepository.aggregateRefundStats(
                BookingStatus.REFUNDING, BookingStatus.REFUNDED, BookingStatus.CONFIRMED))
        .willReturn(new BookingRefundStatsResponse(312, 280));

    // when
    BookingRefundStatsResponse result = bookingGetAdminRefundStatsUseCase.execute();

    // then
    assertThat(result.totalRefunds()).isEqualTo(312);
    assertThat(result.completedRefunds()).isEqualTo(280);
    verify(bookingRepository)
        .aggregateRefundStats(
            BookingStatus.REFUNDING, BookingStatus.REFUNDED, BookingStatus.CONFIRMED);
  }
}
