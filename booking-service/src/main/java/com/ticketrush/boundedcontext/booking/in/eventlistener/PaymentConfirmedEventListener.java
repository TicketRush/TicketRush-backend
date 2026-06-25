package com.ticketrush.boundedcontext.booking.in.eventlistener;

import com.ticketrush.boundedcontext.booking.app.usecase.BookingConfirmUseCase;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.payment.event.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentConfirmedEventListener {

  private final BookingConfirmUseCase bookingConfirmUseCase;
  private final JsonConverter jsonConverter;

  @KafkaListener(topics = PaymentConfirmedEvent.TOPIC, groupId = "booking-group")
  public void handlePaymentConfirmed(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {

    PaymentConfirmedEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), PaymentConfirmedEvent.class);

      log.info(
          "결제 완료 이벤트 수신. 예매 확정 처리. bookingId: {}, paidAt: {}", event.bookingId(), event.paidAt());

      // 중복 수신은 Booking.confirm() 도메인 멱등성으로 안전하게 처리된다.
      bookingConfirmUseCase.execute(event.bookingId(), event.paidAt());

    } catch (Exception e) {
      // 결제는 이미 완료된 상태라 재시도해도 예매 상태가 바뀌지 않는다.
      // 무한 재시도/파티션 블로킹을 피하고, 강한 로그를 남겨 수동 복구가 가능하도록 조치한다.
      // 역직렬화 실패 시 event가 null이라 bookingId를 못 얻으므로, 항상 살아있는
      // envelope.eventId()를 함께 남겨 어떤 메시지가 실패했는지 추적할 수 있게 한다.
      Long failedBookingId = (event != null) ? event.bookingId() : null;
      log.error(
          "[CRITICAL] 결제 완료 이벤트로 예매 확정 중 치명적 오류 발생! "
              + "결제는 완료되었으나 예매 확정에 실패했습니다. 확인이 필요합니다. eventId: {}, bookingId: {}",
          envelope.eventId(),
          failedBookingId,
          e);

      // TODO: 추후 고도화 시, 이 위치에서 DLT로 실패한 이벤트를 재발행

    } finally {
      // 카프카 메시지가 무한 반복되며 파티션을 막는 현상 방지
      ack.acknowledge();
    }
  }
}
