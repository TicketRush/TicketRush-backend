package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.app.dto.response.BookingSummaryResponse;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.dto.request.OffsetPageRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>임계는 Kafka 재시도 소진(약 31초)보다 훨씬 길게 잡아 컨슈머 랙·재배포 중 일시 지연을 고착으로 오탐하지 않게 한다.
 */
@Service
@Transactional(readOnly = true)
public class BookingGetRefundingStuckBookingsUseCase {

  /*
   * 고착 식별(이 조회)과 고착 복구(BookingAdminRetryRefundUseCase의 재시도 가드)는 반드시 같은 임계를 봐야 한다.
   * 키·기본값이 어긋나면 "목록에는 보이는데 재시도는 거절되는" 비정합이 생기므로 여기를 단일 출처로 둔다 (#397).
   */
  public static final String STUCK_THRESHOLD_PROPERTY =
      "${app.booking.refunding-stuck-threshold-minutes:30}";

  private final BookingRepository bookingRepository;
  private final Clock clock;
  private final long stuckThresholdMinutes;

  public BookingGetRefundingStuckBookingsUseCase(
      BookingRepository bookingRepository,
      Clock clock,
      @Value(STUCK_THRESHOLD_PROPERTY) long stuckThresholdMinutes) {
    this.bookingRepository = bookingRepository;
    this.clock = clock;
    this.stuckThresholdMinutes = stuckThresholdMinutes;
  }

  public Page<BookingSummaryResponse> execute(OffsetPageRequest pageRequest) {
    LocalDateTime cutoff = LocalDateTime.now(clock).minusMinutes(stuckThresholdMinutes);

    return bookingRepository
        .findByBookingStatusAndUpdatedAtBefore(
            BookingStatus.REFUNDING,
            cutoff,
            PageRequest.of(
                pageRequest.page(), pageRequest.size(), Sort.by(Sort.Order.asc("updatedAt"))))
        .map(BookingSummaryResponse::from);
  }
}
