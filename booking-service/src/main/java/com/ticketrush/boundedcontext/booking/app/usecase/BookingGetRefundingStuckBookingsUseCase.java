package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.domain.policy.RefundingStuckPolicy;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * REFUNDING에서 임계 시간 이상 멈춰 있는 고착 예매를 관리자가 조회한다 (#397).
 *
 * <p>payment의 PG 통신 실패(성공 여부 불명)는 재시도 소진 후 DLT로 빠지고 종결 이벤트가 끝내 오지 않는다. 이 예매는 돈을 돌려받지 못한 채 좌석이 SOLD로
 * 묶이고 입장까지 차단되지만, 환불 실패 조회({@link BookingGetRefundFailedBookingsUseCase})는 CONFIRMED로 복원된 건만 잡으므로
 * 여기서 별도로 노출한다. 오래 멈춘 순({@code updatedAt} 오름차순)으로 정렬한다.
 *
 * <p>고착 판정 기준은 {@link RefundingStuckPolicy}가 소유한다 — 복구 경로({@link BookingAdminRetryRefundUseCase})와
 * 같은 임계를 봐야 하기 때문이다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingGetRefundingStuckBookingsUseCase {

  private final BookingRepository bookingRepository;
  private final RefundingStuckPolicy refundingStuckPolicy;

  public Page<BookingSummaryResponse> execute(OffsetPageRequest pageRequest) {
    return bookingRepository
        .findByBookingStatusAndUpdatedAtBefore(
            BookingStatus.REFUNDING,
            refundingStuckPolicy.cutoff(),
            PageRequest.of(
                pageRequest.page(), pageRequest.size(), Sort.by(Sort.Order.asc("updatedAt"))))
        .map(BookingSummaryResponse::from);
  }
}
