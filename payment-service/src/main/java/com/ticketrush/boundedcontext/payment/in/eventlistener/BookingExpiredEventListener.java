package com.ticketrush.boundedcontext.payment.in.eventlistener;

import com.ticketrush.boundedcontext.payment.app.facade.PaymentFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.booking.event.BookingExpiredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
    } catch (DataIntegrityViolationException e) {
      // bookingId unique 제약 위반 = 이미 등록된 만료 booking의 중복 수신.
      // 멱등 결과(정상)로 간주하고 ack 하여 불필요한 Kafka 재처리를 막는다.
      log.info("이미 등록된 만료 booking 이벤트(중복 수신). bookingId: {}", event.bookingId());
    } catch (Exception e) {
      Long bookingId = event != null ? event.bookingId() : null;
      log.error("예매 만료 이벤트 처리 실패. bookingId: {}", bookingId, e);
      throw e;
    }

    ack.acknowledge();
  }
}
