package com.ticketrush.boundedcontext.booking.in.eventlistener;

import com.ticketrush.boundedcontext.booking.app.usecase.BookingConfirmUseCase;
import com.ticketrush.boundedcontext.booking.out.apiclient.SeatRestClient;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.payment.event.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentConfirmedEventListener {

  private final BookingConfirmUseCase bookingConfirmUseCase;
  private final SeatRestClient seatRestClient;
  private final JsonConverter jsonConverter;

  @KafkaListener(topics = PaymentConfirmedEvent.TOPIC, groupId = KafkaConsumerGroup.BOOKING)
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
      // 크로스서비스 HTTP 호출이므로 BusinessException이 아닌 RestClient 예외로 도착한다 → isPermanent 미적용, HTTP 상태로 분기.
      try {
        seatRestClient.confirmSold(bookingNumber, event.seatId());
      } catch (HttpClientErrorException e) {
        // 4xx는 재시도해도 결과가 바뀌지 않는 결정적 응답이므로 삼키고 진행(ack)한다.
        // 단 409(이미 SOLD된 중복)만 정상 상태로 보고, 그 외 4xx(401 토큰 오류·404 등)는 설정/요청 오류일 수 있어 CRITICAL로 가시화한다.
        if (e.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
          log.warn(
              "[좌석 SOLD 409] 이미 확정된 좌석(중복 수신). 재시도하지 않는다. "
                  + "eventId: {}, bookingId: {}, seatId: {}, bookingNumber: {}",
              envelope.eventId(),
              event.bookingId(),
              event.seatId(),
              bookingNumber);
        } else {
          log.error(
              "[CRITICAL] 좌석 SOLD 확정이 4xx로 거부됨(설정/요청 오류 의심). 예매는 확정됐으나 좌석 미확정. 확인이 필요합니다. "
                  + "eventId: {}, bookingId: {}, seatId: {}, bookingNumber: {}, status: {}",
              envelope.eventId(),
              event.bookingId(),
              event.seatId(),
              bookingNumber,
              e.getStatusCode(),
              e);
        }
      }
      // 5xx/네트워크(ResourceAccessException 등) 일시 오류는 여기서 잡지 않는다.
      // → 바깥 catch로 전파되어 transient로 분류되고 re-throw → 재시도(멱등)→DLT로 보존된다.

      // 예매 확정 + SOLD(성공 또는 4xx) 완료 시에만 오프셋을 커밋한다(#269 표준).
      ack.acknowledge();

    } catch (Exception e) {
      // #269 표준: 영구(비즈니스/결정적) 실패는 로그 후 ack, 일시(인프라/SOLD 5xx·네트워크) 실패는 re-throw 하여 재시도→DLT로 보존.
      // 역직렬화 실패 시 event가 null이라 bookingId를 못 얻으므로, 항상 살아있는
      // envelope.eventId()를 함께 남겨 어떤 메시지가 실패했는지 추적할 수 있게 한다.
      Long failedBookingId = (event != null) ? event.bookingId() : null;

      if (KafkaConsumerErrorPolicy.isPermanent(e)) {
        if (KafkaConsumerErrorPolicy.isExpectedConflict(e)) {
          log.warn(
              "결제 완료 이벤트 처리 중 예상된 상태충돌(멱등 처리). eventId: {}, bookingId: {}",
              envelope.eventId(),
              failedBookingId,
              e);
        } else {
          log.error(
              "[CRITICAL] 결제 완료 이벤트로 예매 확정 중 치명적 오류 발생! "
                  + "결제는 완료되었으나 예매 확정에 실패했습니다. 확인이 필요합니다. eventId: {}, bookingId: {}",
              envelope.eventId(),
              failedBookingId,
              e);
        }
        ack.acknowledge();
      } else {
        log.warn(
            "결제 완료 이벤트 처리 중 일시적 오류. 재시도합니다. eventId: {}, bookingId: {}",
            envelope.eventId(),
            failedBookingId,
            e);
        throw e;
      }
    }
  }
}
