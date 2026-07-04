package com.ticketrush.boundedcontext.seat.in.eventlistener;

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerErrorPolicy;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.booking.event.BookingCreatedEvent;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingCreatedEventListener {

  private final SeatFacade seatFacade;
  private final JsonConverter jsonConverter;
  private final StringRedisTemplate redisTemplate;

  private static final String IDEMPOTENCY_PREFIX = "idempotency:event:";
  // 이벤트 중복을 방어할 유효 기간
  private static final int IDEMPOTENCY_TTL_HOURS = 24;

  @KafkaListener(topics = BookingCreatedEvent.TOPIC, groupId = KafkaConsumerGroup.SEAT)
  public void handleBookingCreated(@Payload DomainEventEnvelope envelope, Acknowledgment ack) {

    // 1. 멱등성 키 생성
    String eventId = envelope.eventId();
    String idempotencyKey = IDEMPOTENCY_PREFIX + eventId;

    // 2. Redis SETNX를 활용한 중복 체크
    Boolean isFirstMessage =
        redisTemplate
            .opsForValue()
            .setIfAbsent(idempotencyKey, "PROCESSED", Duration.ofHours(IDEMPOTENCY_TTL_HOURS));

    if (Boolean.FALSE.equals(isFirstMessage)) {
      log.info("이미 처리된 카프카 이벤트입니다. 중복 처리를 스킵합니다. eventId: {}", eventId);
      ack.acknowledge();
      return;
    }

    // 3. 실제 비즈니스 로직 실행
    try {
      BookingCreatedEvent event =
          jsonConverter.deserialize(envelope.payload(), BookingCreatedEvent.class);

      seatFacade.tryLockSeat(
          event.bookingId(), event.bookingNumber(), event.seatId(), event.userId());

      // 4. 예외 없이 성공적으로 완료되었을 때만 오프셋 수동 커밋
      ack.acknowledge();

    } catch (Exception e) {
      // #269 표준: 영구(비즈니스/결정적) 실패는 로그 후 ack, 일시(인프라) 실패는 멱등키 롤백 후 re-throw 하여 재시도→DLT로 보존.
      if (KafkaConsumerErrorPolicy.isPermanent(e)) {
        if (KafkaConsumerErrorPolicy.isExpectedConflict(e)) {
          log.warn("좌석 선점 이벤트 처리 중 예상된 상태충돌(멱등 처리). eventId: {}", eventId, e);
        } else {
          log.error("[CRITICAL] 좌석 선점 이벤트 처리 중 치명적 오류 발생! 확인이 필요합니다. eventId: {}", eventId, e);
        }
        // 영구 실패는 재처리해도 결과가 같으므로 멱등키를 유지(처리됨)하고 커밋한다.
        ack.acknowledge();
      } else {
        // 일시 실패는 재시도가 다시 처리할 수 있도록 멱등키를 롤백한 뒤 예외를 다시 던진다.
        log.warn("좌석 선점 이벤트 처리 중 일시적 오류. 멱등키를 롤백하고 재시도합니다. eventId: {}", eventId, e);
        redisTemplate.delete(idempotencyKey);
        throw e;
      }
    }
  }
}
