package com.ticketrush.boundedcontext.seat.in.eventlistener;

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.booking.event.BookingCanceledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingCanceledEventListener {

  private final SeatFacade seatFacade;
  private final JsonConverter jsonConverter;

  @KafkaListener(topics = BookingCanceledEvent.TOPIC, groupId = "seat-group")
  public void handleBookingCanceled(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {
    BookingCanceledEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), BookingCanceledEvent.class);
      seatFacade.releaseBookedSeat(event.seatId(), event.bookingNumber());
    } catch (Exception e) {
      Long bookingId = event != null ? event.bookingId() : null;
      log.error("예매 취소 좌석 반환 이벤트 처리 실패. bookingId: {}", bookingId, e);
      throw e;
    }

    ack.acknowledge();
  }
}
