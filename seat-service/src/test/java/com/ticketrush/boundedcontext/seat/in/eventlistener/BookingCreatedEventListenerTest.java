package com.ticketrush.boundedcontext.seat.in.eventlistener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.DeserializationException;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.BookingCreatedEvent;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class BookingCreatedEventListenerTest {

  @InjectMocks private BookingCreatedEventListener listener;

  @Mock private SeatFacade seatFacade;

  @Mock private JsonConverter jsonConverter;

  @Mock private InboxService inboxService;

  @Mock private Acknowledgment acknowledgment;

  private static final String PAYLOAD = "payload";
  private static final String EVENT_ID = "event-id";
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

  /** Inbox가 최초 수신으로 판정해 비즈니스 콜백을 실행하고 true를 반환하도록 스텁한다. */
  private void givenInboxProcessesFirst() {
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.SEAT), any(DomainEventEnvelope.class), any(Runnable.class)))
        .willAnswer(
            invocation -> {
              invocation.getArgument(2, Runnable.class).run();
              return true;
            });
  }

  @Test
  @DisplayName("최초 수신이면 좌석 선점을 시도하고 오프셋을 커밋한다")
  void handleBookingCreated() {
    // given
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCreatedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();

    // when
    listener.handleBookingCreated(envelope, acknowledgment);

    // then
    verify(seatFacade).tryLockSeat(BOOKING_ID, BOOKING_NUMBER, SEAT_ID, USER_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이미 처리된 이벤트(inbox 중복)면 좌석 선점을 실행하지 않고 오프셋만 커밋한다")
  void handleBookingCreatedSkipsDuplicate() {
    // given: Inbox가 이미 처리됨으로 판정 → 콜백 미실행, false 반환
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCreatedEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.SEAT), any(DomainEventEnvelope.class), any(Runnable.class)))
        .willReturn(false);

    // when
    listener.handleBookingCreated(envelope, acknowledgment);

    // then: 비즈니스 로직은 실행되지 않고 커밋만 한다
    verify(seatFacade, never()).tryLockSeat(anyLong(), anyString(), anyLong(), anyLong());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("동시 중복 수신으로 inbox unique가 경합하면(DIVE) 예외를 전파하지 않고 오프셋을 커밋한다")
  void handleBookingCreatedAcksOnInboxRace() {
    // given
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCreatedEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.SEAT), any(DomainEventEnvelope.class), any(Runnable.class)))
        .willThrow(
            new DuplicateEventException(
                KafkaConsumerGroup.SEAT,
                EVENT_ID,
                new DataIntegrityViolationException("duplicate")));

    // when & then
    assertThatCode(() -> listener.handleBookingCreated(envelope, acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("일시적(인프라) 예외가 발생하면 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT 위임)")
  void handleBookingCreatedRethrowsTransientFailure() {
    // given: 일반 RuntimeException은 일시 실패로 분류된다
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCreatedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new RuntimeException("DB 일시 장애"))
        .given(seatFacade)
        .tryLockSeat(BOOKING_ID, BOOKING_NUMBER, SEAT_ID, USER_ID);

    // when & then
    assertThatThrownBy(() -> listener.handleBookingCreated(envelope, acknowledgment))
        .isInstanceOf(RuntimeException.class);

    // then: 오프셋은 커밋되지 않는다(Inbox 미기록으로 재소비 시 재처리)
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("영구(비즈니스) 예외가 발생하면 예외를 전파하지 않고 오프셋을 커밋한다")
  void handleBookingCreatedAcksPermanentFailure() {
    // given
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCreatedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new BusinessException(ErrorStatus.SEAT_NOT_AVAILABLE))
        .given(seatFacade)
        .tryLockSeat(BOOKING_ID, BOOKING_NUMBER, SEAT_ID, USER_ID);

    // when & then
    assertThatCode(() -> listener.handleBookingCreated(envelope, acknowledgment))
        .doesNotThrowAnyException();

    // then: 영구 실패는 로그 후 커밋되어 파티션 블로킹을 막는다
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이벤트 역직렬화에 실패해도 예외를 전파하지 않고 오프셋을 커밋한다")
  void handleBookingCreatedSwallowsDeserializationException() {
    // given: 실제 JsonConverter가 변환 실패 시 던지는 예외 타입으로 스텁한다
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCreatedEvent.class))
        .willThrow(new DeserializationException(new RuntimeException("broken payload")));

    // when & then: 예외를 리스너 밖으로 전파하지 않는다
    assertThatCode(() -> listener.handleBookingCreated(envelope, acknowledgment))
        .doesNotThrowAnyException();

    // then: 좌석 선점은 호출되지 않고, 오프셋은 커밋된다
    verify(seatFacade, never()).tryLockSeat(anyLong(), anyString(), anyLong(), anyLong());
    verify(acknowledgment).acknowledge();
  }
}
