package com.ticketrush.boundedcontext.seat.in.eventlistener;

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
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

  @KafkaListener(topics = "booking-created-topic", groupId = "seat-group")
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

    } catch (Exception e) {
      log.error("이벤트 처리 중 에러 발생. 재시도를 위해 멱등성 키를 롤백합니다. eventId: {}", eventId, e);
      redisTemplate.delete(idempotencyKey);
      throw e; // 예외를 다시 던져 스프링 카프카의 재시도(Retry) 정책을 태움
    }

    // 4. 예외 없이 성공적으로 완료되었을 때만 오프셋 수동 커밋
    ack.acknowledge();
  }
}
