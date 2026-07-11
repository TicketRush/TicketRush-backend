package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.domain.types.BookingStatus;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.RefundRequestedEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 환불에 실패한 예매의 환불을 다시 시도한다 (#391).
 *
 * <p>{@link BookingCancelMyBookingUseCase}와 같은 경로(CONFIRMED→REFUNDING + {@code
 * RefundRequestedEvent} 발행)를 쓰되, 소유자가 아닌 관리자가 호출하므로 소유권 검증이 없다. 대신 <b>환불 실패 이력이 있는 CONFIRMED
 * 예매</b>로 대상을 제한한다 — 소유자의 의도적 취소와 달리 CS 도구가 정상 예매를 강제 취소해선 안 되기 때문이다. 사용자도 자신의 예매를 다시 취소해 재환불할 수
 * 있으며, 이 API는 CS가 사용자를 대신해 시도하기 위한 것이다.
 *
 * <p>payment-service는 이 이벤트를 받아 결제가 COMPLETED면 PG 환불을 재실행하고, 이미 CANCELED(결제 취소 API로 우회 환불된 경우)면
 * {@code PaymentCanceledEvent}를 재발행해 정합을 self-heal 한다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BookingAdminRetryRefundUseCase {

  private final BookingRepository bookingRepository;
  private final EventPublisher eventPublisher;

  public void execute(Long adminId, String bookingNumber) {
    Booking booking =
        bookingRepository
            .findByBookingNumber(bookingNumber)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    // 이 API의 계약은 "환불 실패 건의 재시도"다. 실패 이력이 없는 예매(또는 이미 환불이 진행·종결된 예매)에
    // 걸리면 관리자 오조작만으로 정상 예매가 강제 취소된다. 소유자의 의도적 취소와는 계약이 다르므로 여기서 막는다.
    if (booking.getRefundFailedAt() == null
        || booking.getBookingStatus() != BookingStatus.CONFIRMED) {
      throw new BusinessException(ErrorStatus.BOOKING_REFUND_RETRY_NOT_ALLOWED);
    }

    booking.requestRefund();

    // 실제 PG 환불을 유발하는 관리자 행위다. 누가 누구의 예매를 건드렸는지 추적 가능해야 한다.
    log.info(
        "[ADMIN-AUDIT] 환불 재시도. adminId: {}, bookingNumber: {}, bookingId: {}, userId: {}",
        adminId,
        booking.getBookingNumber(),
        booking.getId(),
        booking.getUserId());

    eventPublisher.publish(
        new RefundRequestedEvent(
            booking.getId(),
            booking.getBookingNumber(),
            booking.getSeatId(),
            booking.getUserId(),
            LocalDateTime.now()));
  }
}
