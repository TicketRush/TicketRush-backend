package com.ticketrush.boundedcontext.booking.in.eventlistener;

import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.shared.booking.event.BookingExpiredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpiredEventListener {

  private final EventPublisher eventPublisher;

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleBookingExpiredEvent(BookingExpiredEvent event) {
    try {
      eventPublisher.publish(event);
      log.info("Kafka 예매 만료 이벤트 발행 성공. bookingId: {}", event.bookingId());
    } catch (Exception e) {
      // TODO: Outbox 패턴 도입. Kafka 발행 실패 시 실패한 이벤트를 Outbox DB에 저장하고 배치로 재시도하는 로직 추가
      log.error(
          "[CRITICAL] Kafka 예매 만료 이벤트 발행 실패. payment-service 정합성 확인 필요! bookingId: {}",
          event.bookingId(),
          e);
    }
  }
}
