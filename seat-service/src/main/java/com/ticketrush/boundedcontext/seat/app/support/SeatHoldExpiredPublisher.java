package com.ticketrush.boundedcontext.seat.app.support;

import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.shared.seat.event.SeatHoldExpiredEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 좌석 hold 만료 시 {@link SeatHoldExpiredEvent}를 발행한다(Outbox 경유).
 *
 * <p>booking-service가 이 이벤트를 받아 예매를 EXPIRED로 전이한다. {@link EventPublisher}(Outbox 구현)는 활성 트랜잭션을
 * 요구하므로 {@code @Transactional} 유스케이스 안에서만 호출해야 한다.
 *
 * <p><b>주의:</b> {@link Seat#releaseHold()}가 {@code bookingNumber}/{@code holdExpiredAt}을 null로
 * 클리어하므로, 이 메서드는 반드시 {@code releaseHold()} 호출 <b>전에</b> 불러 식별자를 캡처해야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatHoldExpiredPublisher {

  private final EventPublisher eventPublisher;

  public void publish(Seat seat) {
    String bookingNumber = seat.getBookingNumber();
    if (bookingNumber == null || bookingNumber.isBlank()) {
      log.warn(
          "HOLD 좌석에 bookingNumber가 없어 SeatHoldExpiredEvent 발행을 스킵합니다. seatId: {}", seat.getId());
      return;
    }

    LocalDateTime expiredAt =
        seat.getHoldExpiredAt() != null ? seat.getHoldExpiredAt() : LocalDateTime.now();
    eventPublisher.publish(new SeatHoldExpiredEvent(seat.getId(), bookingNumber, expiredAt));
  }
}
