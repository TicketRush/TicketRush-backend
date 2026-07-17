package com.ticketrush.boundedcontext.booking.app.usecase;

import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import com.ticketrush.boundedcontext.booking.out.repository.BookingRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.RefundRequestedEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class BookingCancelMyBookingUseCase {

  private final BookingRepository bookingRepository;
  private final EventPublisher eventPublisher;

  public void execute(Long userId, String bookingNumber) {
    Booking booking =
        bookingRepository
            .findByBookingNumberAndUserId(bookingNumber, userId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.BOOKING_NOT_FOUND));

    // 결제 완료 예매의 취소는 곧바로 종결하지 않고 환불을 요청한다(CONFIRMED→REFUNDING). 좌석 반환·예매 종결은 환불 성공 이벤트에 매단다(#91).
    booking.requestRefund();

    eventPublisher.publish(
        new RefundRequestedEvent(
            booking.getId(),
            booking.getBookingNumber(),
            booking.getSeatId(),
            booking.getUserId(),
            LocalDateTime.now()));
  }
}
