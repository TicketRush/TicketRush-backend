package com.ticketrush.boundedcontext.seat.in.eventlistener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.BookingCreatedEvent;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class BookingCreatedEventListenerTest {

  @InjectMocks private BookingCreatedEventListener listener;

  @Mock private SeatFacade seatFacade;

  @Mock private JsonConverter jsonConverter;

  @Mock private StringRedisTemplate redisTemplate;

  @Mock private ValueOperations<String, String> valueOperations;

  @Mock private Acknowledgment acknowledgment;

  private static final String PAYLOAD = "payload";
  private static final String EVENT_ID = "event-id";
  private static final String IDEMPOTENCY_KEY = "idempotency:event:" + EVENT_ID;
  private static final Long BOOKING_ID = 10L;
  private static final String BOOKING_NUMBER = "BOOK-1234";
  private static final Long SEAT_ID = 3L;
  private static final Long USER_ID = 4L;

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        EVENT_ID, "BookingCreatedEvent", Instant.now(), BookingCreatedEvent.TOPIC, PAYLOAD, null);
  }

  private BookingCreatedEvent event() {
    return new BookingCreatedEvent(BOOKING_ID, BOOKING_NUMBER, SEAT_ID, 5L, USER_ID);
  }

  private void givenFirstMessage() {
    given(redisTemplate.opsForValue()).willReturn(valueOperations);
    given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
        .willReturn(true);
  }

  @Test
  @DisplayName("최초 수신 이벤트는 좌석 선점을 시도하고 오프셋을 커밋한다")
  void handleBookingCreated() {
    // given
    givenFirstMessage();
    DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCreatedEvent.class)).willReturn(event());

    // when
    listener.handleBookingCreated(envelope, acknowledgment);

    // then
    verify(seatFacade).tryLockSeat(BOOKING_ID, BOOKING_NUMBER, SEAT_ID, USER_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이미 처리된(멱등키 존재) 이벤트는 스킵하고 오프셋만 커밋한다")
  void handleBookingCreatedSkipsDuplicate() {
    // given: SETNX가 false(이미 존재)를 반환
    given(redisTemplate.opsForValue()).willReturn(valueOperations);
    given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
        .willReturn(false);
    DomainEventEnvelope envelope = envelope();

    // when
    listener.handleBookingCreated(envelope, acknowledgment);

    // then: 비즈니스 로직은 실행되지 않고 커밋만 한다
    verify(seatFacade, never()).tryLockSeat(anyLong(), anyString(), anyLong(), anyLong());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("일시적(인프라) 예외가 발생하면 멱등키를 롤백하고 예외를 전파하며 커밋하지 않는다")
  void handleBookingCreatedRollsBackAndRethrowsTransientFailure() {
    // given
    givenFirstMessage();
    DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCreatedEvent.class)).willReturn(event());
    willThrow(new RuntimeException("DB 일시 장애"))
        .given(seatFacade)
        .tryLockSeat(BOOKING_ID, BOOKING_NUMBER, SEAT_ID, USER_ID);

    // when & then
    assertThatThrownBy(() -> listener.handleBookingCreated(envelope, acknowledgment))
        .isInstanceOf(RuntimeException.class);

    // then: 재시도가 다시 처리할 수 있도록 멱등키를 롤백하고, 오프셋은 커밋하지 않는다
    verify(redisTemplate).delete(IDEMPOTENCY_KEY);
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("영구(비즈니스) 예외가 발생하면 멱등키를 유지하고 예외를 전파하지 않으며 커밋한다")
  void handleBookingCreatedAcksPermanentFailure() {
    // given
    givenFirstMessage();
    DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCreatedEvent.class)).willReturn(event());
    willThrow(new BusinessException(ErrorStatus.SEAT_NOT_AVAILABLE))
        .given(seatFacade)
        .tryLockSeat(BOOKING_ID, BOOKING_NUMBER, SEAT_ID, USER_ID);

    // when & then
    assertThatCode(() -> listener.handleBookingCreated(envelope, acknowledgment))
        .doesNotThrowAnyException();

    // then: 영구 실패는 재처리해도 같으므로 멱등키를 롤백하지 않고 커밋한다
    verify(redisTemplate, never()).delete(anyString());
    verify(acknowledgment).acknowledge();
  }
}
