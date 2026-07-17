package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 성공(결제 취소) 이벤트를 받아 예매를 REFUNDED로 종결한다 (#49).
 *
 * <p>환불 성공({@code PaymentCanceledEvent})에만 매달려 있어 refund-first 정합성을 지킨다. 존재하지 않는 예매나 이미 종결된 상태는
 * 멱등하게 처리한다({@link Booking#markRefunded()}).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BookingMarkRefundedUseCase {

  private final BookingRepository bookingRepository;

  public void execute(Long bookingId) {
    Booking booking = bookingRepository.findById(bookingId).orElse(null);

    if (booking == null) {
      log.warn("환불 예매 종결 스킵. 존재하지 않는 bookingId={}", bookingId);
      return;
    }

    boolean refunded = booking.markRefunded();
    if (refunded) {
      log.debug("환불 예매 종결 처리. bookingId: {}", bookingId);
    } else {
      // 환불은 됐으나 예매가 종결 불가 상태(CANCELED/PENDING/EXPIRED). 이상 상황이므로 가시화한다.
      log.warn(
          "환불 예매 종결 스킵: 종결 불가 상태입니다. bookingId: {}, status: {}",
          bookingId,
          booking.getBookingStatus());
    }
  }
}
