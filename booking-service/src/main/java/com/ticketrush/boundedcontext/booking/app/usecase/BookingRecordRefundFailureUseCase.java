package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PG 환불 최종 실패({@code RefundFailedEvent})를 받아 예매를 CONFIRMED로 복원하고 실패 시각을 기록한다 (#391).
 *
 * <p>환불이 실패했다는 건 취소가 성사되지 않았다는 뜻이므로, 환불 요청으로 REFUNDING이던 예매를 원래의 CONFIRMED로 되돌린다({@link
 * Booking#recordRefundFailure(LocalDateTime)}). 좌석은 환불되지 않았으므로 SOLD를 유지한다(반환하지 않는다). 존재하지 않는 예매나 이미
 * 복원된 예매, 또는 전이 불가 상태(REFUNDED로 이미 종결 등)는 멱등하게 처리한다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BookingRecordRefundFailureUseCase {

  private final BookingRepository bookingRepository;

  public void execute(Long bookingId, LocalDateTime failedAt) {
    Booking booking = bookingRepository.findById(bookingId).orElse(null);

    if (booking == null) {
      log.warn("환불 실패 기록 스킵. 존재하지 않는 bookingId={}", bookingId);
      return;
    }

    boolean restored = booking.recordRefundFailure(failedAt);
    if (restored) {
      // 환불에 실패해 사용자가 대금을 돌려받지 못한 예매다. 수동 처리가 필요하므로 관리자 확인을 위해 가시화한다.
      log.error(
          "[CRITICAL] 환불 실패로 예매를 CONFIRMED로 복원하고 실패 시각을 기록. 관리자 확인 필요. bookingId: {}", bookingId);
    } else {
      // REFUNDING이 아니어서 복원 대상이 아니다(REFUNDED로 이미 종결 등). 교차 경로/이상 상황이므로 가시화한다.
      log.warn(
          "환불 실패 기록 스킵: 전이 불가 상태입니다. bookingId: {}, status: {}",
          bookingId,
          booking.getBookingStatus());
    }
  }
}
