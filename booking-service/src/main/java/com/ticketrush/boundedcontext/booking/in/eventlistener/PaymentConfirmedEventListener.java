package com.ticketrush.boundedcontext.booking.in.eventlistener;

import com.ticketrush.boundedcontext.booking.app.usecase.BookingConfirmUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingGetBookingNumberUseCase;
import com.ticketrush.boundedcontext.booking.app.usecase.BookingPublishSeatConfirmFailedUseCase;
import com.ticketrush.boundedcontext.booking.out.apiclient.SeatRestClient;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.payment.event.PaymentConfirmedEvent;
import java.time.LocalDateTime;
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
  private final BookingGetBookingNumberUseCase bookingGetBookingNumberUseCase;
  private final BookingPublishSeatConfirmFailedUseCase bookingPublishSeatConfirmFailedUseCase;
  private final SeatRestClient seatRestClient;
  private final JsonConverter jsonConverter;
  private final InboxService inboxService;

  @KafkaListener(topics = PaymentConfirmedEvent.TOPIC, groupId = KafkaConsumerGroup.BOOKING)
  public void handlePaymentConfirmed(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {

    PaymentConfirmedEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), PaymentConfirmedEvent.class);
      final Long bookingId = event.bookingId();
      final Long seatId = event.seatId();
      final LocalDateTime paidAt = event.paidAt();
      // 실제 결제 금액을 예매에 박아 둔다 (#561). 관리자 매출 집계가 공연의 현재 가격을 되묻지 않게 하는 값이다.
      final Long paidAmount = event.amount();

      log.info("결제 완료 이벤트 수신. 예매 확정 처리. bookingId: {}, paidAt: {}", bookingId, paidAt);

      // 1. 예매 확정(DB 상태 전이)만 Inbox로 감싼다(#110) — 확정과 Inbox 기록을 한 트랜잭션으로 원자 커밋한다.
      //    커밋 후 좌석 SOLD HTTP 호출은 크로스서비스라 이 원자 단위에 포함할 수 없다.
      //    결제 컨텍스트의 seatId가 예매의 seatId와 일치할 때만 확정된다(불일치 시 예외).
      boolean raced = false;
      boolean processed = false;
      try {
        processed =
            inboxService.runIfFirst(
                KafkaConsumerGroup.BOOKING,
                envelope,
                () -> bookingConfirmUseCase.execute(bookingId, paidAt, seatId, paidAmount));
      } catch (DuplicateEventException e) {
        // 동시 중복 수신으로 inbox unique가 경합함(#110). 확정은 경합에서 이긴 처리가 커밋하므로 여기선 확정을
        // 재수행하지 않되, SOLD는 아래에서 멱등하게 재시도해 유실을 막는다.
        raced = true;
        log.info("동시 중복 수신(inbox unique 경합). eventId: {}", envelope.eventId());
      }

      if (processed) {
        log.info("예매 확정 처리 완료. bookingId: {}", bookingId);
      } else if (!raced) {
        log.info(
            "이미 처리된 결제 완료 이벤트(inbox 중복 스킵). eventId: {}, bookingId: {}",
            envelope.eventId(),
            bookingId);
      }

      // 2. bookingNumber를 재조회한다. 확정 여부·경합 여부와 무관하게 확정된 예매에서 다시 읽어,
      //    일시적 SOLD 실패로 재소비되거나 경합 패자가 되더라도 SOLD 재시도가 유실되지 않게 한다.
      String bookingNumber = bookingGetBookingNumberUseCase.execute(bookingId);

      // 3. 예매 확정 커밋 후 좌석 SOLD 확정을 동기 호출한다(Inbox 밖). SOLD는 409(이미 SOLD) 멱등이라 이중 호출도 안전하다.
      // 크로스서비스 HTTP 호출이므로 BusinessException이 아닌 RestClient 예외로 도착한다 → isPermanent 미적용, HTTP 상태로 분기.
      try {
        seatRestClient.confirmSold(bookingNumber, seatId);
      } catch (HttpClientErrorException e) {
        // 4xx는 재시도해도 결과가 바뀌지 않는 결정적 응답이므로 삼키고 진행(ack)한다.
        // 409 중 보상이 필요한 것은 SEAT_409_003("그 좌석이 이 예매의 것이 아님") 하나뿐이라, 상태 코드가
        // 아니라 응답 본문의 code로 가른다(#489). 상태 코드만 보면 두 경우에 오판한다.
        //   - 버전 스큐: #489 이전 seat-service는 정상 중복(이미 같은 예매로 SOLD)에도 409를 준다. 배포
        //     순서가 뒤바뀌거나 seat만 롤백되면 그 정상 중복이 보상 신호로 둔갑해 멀쩡한 결제가 환불된다.
        //   - COMMON_409: GlobalExceptionHandler가 OptimisticLockingFailureException을 409로 바꾼다.
        //     이건 일시 오류라 보상 대상이 아니다.
        // code를 못 읽으면(본문 없음) 보상하지 않는다 — 잘못된 환불보다 놓친 알림이 낫다.
        // 그 외 4xx(401 토큰 오류·404 등)는 설정/요청 오류일 수 있어 CRITICAL로 가시화한다.
        if (isSeatNotOwned(e)) {
          log.error(
              "[CRITICAL] 좌석 SOLD 확정이 409로 거부됨. 결제·예매는 확정됐으나 좌석이 이 예매의 것이 아닙니다"
                  + "(만료 해제 또는 타 예매 점유). 보상이 필요합니다. "
                  + "eventId: {}, bookingId: {}, seatId: {}, bookingNumber: {}",
              envelope.eventId(),
              bookingId,
              seatId,
              bookingNumber,
              e);

          // 로그 뒤에 발행한다 — 발행이 실패해도 CRITICAL 한 줄은 반드시 남는다. 발행 실패는 감싸지 않고
          // 바깥 catch로 보내 #269 분류에 맡긴다(DB 장애=일시→재시도→DLT, BOOKING_NOT_FOUND=영구→ack).
          bookingPublishSeatConfirmFailedUseCase.execute(bookingId);
        } else if (e.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
          log.warn(
              "[좌석 SOLD 409] 보상 대상이 아닌 상태충돌(구버전 정상 중복·낙관적 락 등). 재시도하지 않는다. "
                  + "eventId: {}, bookingId: {}, seatId: {}, bookingNumber: {}, body: {}",
              envelope.eventId(),
              bookingId,
              seatId,
              bookingNumber,
              e.getResponseBodyAsString());
        } else {
          log.error(
              "[CRITICAL] 좌석 SOLD 확정이 4xx로 거부됨(설정/요청 오류 의심). 예매는 확정됐으나 좌석 미확정. 확인이 필요합니다. "
                  + "eventId: {}, bookingId: {}, seatId: {}, bookingNumber: {}, status: {}",
              envelope.eventId(),
              bookingId,
              seatId,
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
          // 예매 확정뿐 아니라 좌석 확정 실패 신호 발행(#489)도 이 경로로 떨어지므로, 문구는 어느 단계인지
          // 단정하지 않는다 — 스택트레이스가 그것을 말한다.
          log.error(
              "[CRITICAL] 결제 완료 이벤트 처리 중 치명적 오류 발생! "
                  + "결제는 완료되었으나 후속 처리(예매 확정 또는 좌석 확정 실패 신호 발행)가 실패했습니다. "
                  + "확인이 필요합니다. eventId: {}, bookingId: {}",
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

  /**
   * seat-service가 "그 좌석이 이 예매의 것이 아님"({@code SEAT_409_003})으로 거부했는가 (#489).
   *
   * <p>응답 본문에서 코드 문자열을 직접 찾는다. {@code getResponseBodyAs(...)}는 RestClient가 주입하는 body converter에 의존해
   * 예외를 직접 만든 단위 테스트에서 {@code IllegalStateException}으로 터지는데, 여기 필요한 판정은 "그 코드가 왔는가" 하나뿐이라 역직렬화까지 갈
   * 이유가 없다.
   *
   * <p>본문이 없거나 코드가 없으면 {@code false}다 — 호출부는 그것을 "보상 대상 아님"으로 읽는다. 이 신호는 자동 환불로 이어지므로(#492) 근거가
   * 불확실할 때 발행하지 않는 쪽이 안전하다: 잘못된 환불보다 놓친 알림이 낫다.
   */
  private boolean isSeatNotOwned(HttpClientErrorException e) {
    return e.getResponseBodyAsString().contains(ErrorStatus.SEAT_CONFIRM_NOT_OWNED.getCode());
  }
}
