package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PG 환불 최종 실패({@code RefundFailedEvent})를 받아 예매를 REFUND_FAILED로 보상 확정한다 (#91).
 *
 * <p>환불 요청으로 REFUNDING 상태였던 예매를 되돌린다({@link Booking#markRefundFailed()}). 존재하지 않는 예매나 이미
 * REFUND_FAILED, 또는 전이 불가 상태(REFUNDED로 이미 종결 등)는 멱등하게 처리한다. 좌석은 환불이 실패했으므로 SOLD를 유지한다(반환하지 않는다).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BookingMarkRefundFailedUseCase {

  private final BookingRepository bookingRepository;

  public void execute(Long bookingId) {
    Booking booking = bookingRepository.findById(bookingId).orElse(null);

    if (booking == null) {
      log.warn("환불 실패 보상 스킵. 존재하지 않는 bookingId={}", bookingId);
      return;
    }

    boolean compensated = booking.markRefundFailed();
    if (compensated) {
      // 환불이 최종 실패해 수동 처리가 필요한 예매다. 관리자 확인을 위해 가시화한다.
      log.error("[CRITICAL] 환불 실패로 예매를 REFUND_FAILED 확정. 관리자 확인 필요. bookingId: {}", bookingId);
    } else {
      // REFUNDING이 아니어서 보상 전이 불가(REFUNDED로 이미 종결 등). 교차 경로/이상 상황이므로 가시화한다.
      log.warn(
          "환불 실패 보상 스킵: 전이 불가 상태입니다. bookingId: {}, status: {}",
          bookingId,
          booking.getBookingStatus());
    }
  }
}
