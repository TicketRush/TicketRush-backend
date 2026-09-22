package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingRefundStatsResponse;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 환불 요약 통계 (#675). 예매가 환불 결과를 이미 보유하므로 DB 집계 한 번으로 끝나고 원격 호출이 없다.
 *
 * <p>모집단은 {@code BookingGetAdminRefundsUseCase}의 필터 없는 조회와 같다 — 두 쿼리의 조건이 갈리면 카드의 전체 건수와 목록의 {@code
 * total_elements}가 어긋난다.
 *
 * <p>기존 {@code BookingAdminStatsResponse.canceledBookings}를 환불 건수로 재사용하지 않는다. 그 값은 {@code CANCELED
 * + REFUNDED}라 결제 전 취소가 섞여 있어 환불 집계가 아니다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingGetAdminRefundStatsUseCase {

  private final BookingRepository bookingRepository;

  /**
   * 전체 환불 건수와 환불 완료 건수를 반환한다.
   *
   * <p>전체는 환불 진행 중 + 환불 완료 + 미해결 실패다. 목록의 {@code refundStatus} 필터는 이 집계에 적용되지 않는다 — 카드는 필터와 무관하게 항상
   * 전체 모집단을 보여준다.
   */
  public BookingRefundStatsResponse execute() {
    return bookingRepository.aggregateRefundStats(
        BookingStatus.REFUNDING, BookingStatus.REFUNDED, BookingStatus.CONFIRMED);
  }
}
