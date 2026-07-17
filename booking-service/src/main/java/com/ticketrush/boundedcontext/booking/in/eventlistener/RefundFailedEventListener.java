package com.ticketrush.boundedcontext.booking.in.eventlistener;

import com.ticketrush.boundedcontext.booking.app.usecase.BookingRecordRefundFailureUseCase;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.payment.event.RefundFailedEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * PG 환불 최종 실패 이벤트를 수신해 예매를 CONFIRMED로 복원하고 실패 시각을 기록한다 (#391).
 *
 * <p>환불이 실패했다는 건 취소가 성사되지 않았다는 뜻이므로, 환불 요청으로 REFUNDING이던 예매를 원래의 CONFIRMED로 되돌린다. 좌석은 환불되지 않았으므로
 * SOLD를 유지한다(반환 이벤트를 발행하지 않는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundFailedEventListener {

  private final BookingRecordRefundFailureUseCase bookingRecordRefundFailureUseCase;
  private final JsonConverter jsonConverter;
  private final InboxService inboxService;

  @KafkaListener(topics = RefundFailedEvent.TOPIC, groupId = KafkaConsumerGroup.BOOKING)
  public void handleRefundFailed(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {
    RefundFailedEvent event = null;

    try {
      event = jsonConverter.deserialize(envelope.payload(), RefundFailedEvent.class);
      final Long bookingId = event.bookingId();
      final LocalDateTime failedAt = event.failedAt();

      // Inbox로 중복 처리를 방지한다(#110). 최초 수신일 때만 복원을 수행하고 처리와 Inbox 기록을 한 트랜잭션으로 묶는다.
      boolean processed =
          inboxService.runIfFirst(
              KafkaConsumerGroup.BOOKING,
              envelope,
              () -> bookingRecordRefundFailureUseCase.execute(bookingId, failedAt));

      if (!processed) {
        log.info(
            "이미 처리된 환불 실패 이벤트(inbox 중복 스킵). eventId: {}, bookingId: {}",
            envelope.eventId(),
            bookingId);
      }

      // 성공 시에만 오프셋을 커밋한다(#269 표준).
      ack.acknowledge();

    } catch (DuplicateEventException e) {
      // 동시 중복 수신으로 inbox unique가 경합함 → 멱등(중복) 정상으로 간주하고 커밋한다(#110).
      log.info("동시 중복 수신(inbox unique 경합). eventId: {}", envelope.eventId());
      ack.acknowledge();
    } catch (Exception e) {
      // #269 표준: 영구(비즈니스/결정적) 실패는 로그 후 ack, 일시(인프라) 실패는 re-throw 하여 재시도→DLT로 보존.
      Long bookingId = event != null ? event.bookingId() : null;

      if (KafkaConsumerErrorPolicy.isPermanent(e)) {
        if (KafkaConsumerErrorPolicy.isExpectedConflict(e)) {
          log.warn("환불 실패 보상 이벤트 처리 중 예상된 상태충돌(멱등 처리). bookingId: {}", bookingId, e);
        } else {
          log.error("[CRITICAL] 환불 실패 보상 이벤트 처리 실패! 확인이 필요합니다. bookingId: {}", bookingId, e);
        }
        ack.acknowledge();
      } else {
        log.warn("환불 실패 보상 이벤트 처리 중 일시적 오류. 재시도합니다. bookingId: {}", bookingId, e);
        throw e;
      }
    }
  }
}
