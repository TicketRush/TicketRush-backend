package com.ticketrush.boundedcontext.booking.in.eventlistener;

import com.ticketrush.boundedcontext.booking.app.usecase.BookingConfirmUseCase;
import com.ticketrush.boundedcontext.booking.out.apiclient.SeatRestClient;
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
  private final SeatRestClient seatRestClient;
  private final JsonConverter jsonConverter;

  @KafkaListener(topics = PaymentConfirmedEvent.TOPIC, groupId = "booking-group")
  public void handlePaymentConfirmed(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {

    PaymentConfirmedEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), PaymentConfirmedEvent.class);

      log.info(
          "결제 완료 이벤트 수신. 예매 확정 처리. bookingId: {}, paidAt: {}", event.bookingId(), event.paidAt());

      // 결제 컨텍스트의 seatId가 예매의 seatId와 일치할 때만 확정된다(불일치 시 예외).
      // 중복 수신은 Booking.confirm() 도메인 멱등성으로 안전하게 처리된다.
      String bookingNumber =
          bookingConfirmUseCase.execute(event.bookingId(), event.paidAt(), event.seatId());

      // 예매 확정 트랜잭션 커밋 후 좌석 SOLD 확정을 동기 호출한다.
      // SOLD 호출 실패는 예매 확정과 분리해 처리한다(예매는 확정됐으나 좌석만 미확정인 상태를 구분 추적).
      try {
        seatRestClient.confirmSold(bookingNumber, event.seatId());
      } catch (Exception e) {
        // SOLD 호출 실패 종류를 구분하지 않고 모두 swallow한다(파티션 블로킹 방지).
        // - 중복 수신으로 이미 SOLD된 좌석: seat-service가 409(SEAT_NOT_AVAILABLE)를 반환하며 이는 정상 상태다.
        // - 네트워크/5xx 실패: 예매는 확정됐으나 좌석은 미확정인 불일치 상태로, 수동 복구가 필요할 수 있다.
        // 현재는 두 경우를 같은 [CRITICAL] 로그로 남긴다. 실패 종류 구분과 자동 보정은 후속 고도화 대상이다.
        log.error(
            "[CRITICAL] 예매는 확정됐으나 좌석 SOLD 확정에 실패했습니다. 확인이 필요합니다. "
                + "eventId: {}, bookingId: {}, seatId: {}, bookingNumber: {}",
            envelope.eventId(),
            event.bookingId(),
            event.seatId(),
            bookingNumber,
            e);

        // TODO: 추후 고도화 시, 좌석 미확정 상태를 DLT/아웃박스로 재처리해 정합성을 보정
      }

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
