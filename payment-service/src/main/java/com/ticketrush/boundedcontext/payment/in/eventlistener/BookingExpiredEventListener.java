package com.ticketrush.boundedcontext.payment.in.eventlistener;

import com.ticketrush.boundedcontext.payment.app.facade.PaymentFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.booking.event.BookingExpiredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/*
 * booking-service가 발행하는 예매 만료 이벤트를 수신해 만료 bookingId를 영속화한다. (#224)
 * 이후 PaymentConfirmUseCase가 이 기록으로 만료 booking에 대한 confirm 요청을 차단한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpiredEventListener {

  private final PaymentFacade paymentFacade;
  private final JsonConverter jsonConverter;

  @KafkaListener(topics = BookingExpiredEvent.TOPIC, groupId = "payment-group")
  public void handleBookingExpired(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {
    BookingExpiredEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), BookingExpiredEvent.class);
      paymentFacade.registerExpiredBooking(event.bookingId(), event.expiredAt());
    } catch (Exception e) {
      Long bookingId = event != null ? event.bookingId() : null;
      log.error("예매 만료 이벤트 처리 실패. bookingId: {}", bookingId, e);
      throw e;
    }

    ack.acknowledge();
  }
}
