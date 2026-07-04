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
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.booking.event.BookingCanceledEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class BookingCanceledEventListenerTest {

  @InjectMocks private BookingCanceledEventListener listener;

  @Mock private SeatFacade seatFacade;

  @Mock private JsonConverter jsonConverter;

  @Mock private InboxService inboxService;

  @Mock private Acknowledgment acknowledgment;

  private static final String PAYLOAD = "payload";
  private static final Long SEAT_ID = 3L;
  private static final String BOOKING_NUMBER = "BOOK-1234";

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        "event-id",
        BookingCanceledEvent.EVENT_NAME,
        Instant.now(),
        BookingCanceledEvent.TOPIC,
        PAYLOAD,
        null);
  }

  private BookingCanceledEvent event() {
    return new BookingCanceledEvent(
        10L, BOOKING_NUMBER, SEAT_ID, 4L, LocalDateTime.of(2026, 5, 22, 10, 30));
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
  @DisplayName("최초 수신이면 좌석을 반환하고 오프셋을 커밋한다")
  void handleBookingCanceled() {
    // given
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCanceledEvent.class)).willReturn(event());
    givenInboxProcessesFirst();

    // when
    listener.handleBookingCanceled(envelope, acknowledgment);

    // then
    verify(seatFacade).releaseBookedSeat(SEAT_ID, BOOKING_NUMBER);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이미 처리된 이벤트(inbox 중복)면 좌석 반환을 실행하지 않고 오프셋만 커밋한다")
  void handleBookingCanceledSkipsDuplicate() {
    // given
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCanceledEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.SEAT), any(DomainEventEnvelope.class), any(Runnable.class)))
        .willReturn(false);

    // when
    listener.handleBookingCanceled(envelope, acknowledgment);

    // then
    verify(seatFacade, never()).releaseBookedSeat(anyLong(), anyString());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("동시 중복 수신으로 inbox unique가 경합하면(DIVE) 예외를 전파하지 않고 오프셋을 커밋한다")
  void handleBookingCanceledAcksOnInboxRace() {
    // given
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCanceledEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.SEAT), any(DomainEventEnvelope.class), any(Runnable.class)))
        .willThrow(
            new DuplicateEventException(
                KafkaConsumerGroup.SEAT,
                "event-id",
                new DataIntegrityViolationException("duplicate")));

    // when & then
    assertThatCode(() -> listener.handleBookingCanceled(envelope, acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("일시적(인프라) 예외가 발생하면 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT 위임)")
  void handleBookingCanceledRethrowsTransientFailure() {
    // given
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCanceledEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new RuntimeException("DB 일시 장애"))
        .given(seatFacade)
        .releaseBookedSeat(SEAT_ID, BOOKING_NUMBER);

    // when & then
    assertThatThrownBy(() -> listener.handleBookingCanceled(envelope, acknowledgment))
        .isInstanceOf(RuntimeException.class);

    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("영구(비즈니스) 예외가 발생하면 예외를 전파하지 않고 오프셋을 커밋한다")
  void handleBookingCanceledAcksPermanentFailure() {
    // given
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, BookingCanceledEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new BusinessException(ErrorStatus.SEAT_NOT_FOUND))
        .given(seatFacade)
        .releaseBookedSeat(SEAT_ID, BOOKING_NUMBER);

    // when & then
    assertThatCode(() -> listener.handleBookingCanceled(envelope, acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }
}
