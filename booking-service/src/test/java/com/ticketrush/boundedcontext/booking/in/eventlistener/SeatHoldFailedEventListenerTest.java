package com.ticketrush.boundedcontext.booking.in.eventlistener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.booking.app.usecase.BookingCancelUseCase;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.seat.event.SeatHoldFailedEvent;
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
class SeatHoldFailedEventListenerTest {

  @InjectMocks private SeatHoldFailedEventListener listener;

  @Mock private BookingCancelUseCase bookingCancelUseCase;

  @Mock private JsonConverter jsonConverter;

  @Mock private InboxService inboxService;

  @Mock private Acknowledgment acknowledgment;

  private static final String PAYLOAD = "payload";
  private static final Long BOOKING_ID = 10L;

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        "event-id",
        SeatHoldFailedEvent.class.getSimpleName(),
        Instant.now(),
        SeatHoldFailedEvent.TOPIC,
        PAYLOAD,
        null);
  }

  private SeatHoldFailedEvent event() {
    return new SeatHoldFailedEvent(BOOKING_ID, 3L, "좌석 선점 실패");
  }

  /** Inbox가 최초 수신으로 판정해 비즈니스 콜백을 실행하고 true를 반환하도록 스텁한다. */
  private void givenInboxProcessesFirst() {
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.BOOKING),
                any(DomainEventEnvelope.class),
                any(Runnable.class)))
        .willAnswer(
            invocation -> {
              invocation.getArgument(2, Runnable.class).run();
              return true;
            });
  }

  @Test
  @DisplayName("최초 수신이면 예매를 취소하고 오프셋을 커밋한다")
  void handleSeatHoldFailed() {
    // given
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, SeatHoldFailedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();

    // when
    listener.handleSeatHoldFailed(envelope, acknowledgment);

    // then
    verify(bookingCancelUseCase).execute(BOOKING_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이미 처리된 이벤트(inbox 중복)면 예매 취소를 실행하지 않고 오프셋만 커밋한다")
  void handleSeatHoldFailedSkipsDuplicate() {
    // given
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, SeatHoldFailedEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.BOOKING),
                any(DomainEventEnvelope.class),
                any(Runnable.class)))
        .willReturn(false);

    // when
    listener.handleSeatHoldFailed(envelope, acknowledgment);

    // then
    verify(bookingCancelUseCase, never()).execute(anyLong());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("동시 중복 수신으로 inbox unique가 경합하면(DIVE) 예외를 전파하지 않고 오프셋을 커밋한다")
  void handleSeatHoldFailedAcksOnInboxRace() {
    // given
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, SeatHoldFailedEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.BOOKING),
                any(DomainEventEnvelope.class),
                any(Runnable.class)))
        .willThrow(
            new DuplicateEventException(
                KafkaConsumerGroup.BOOKING,
                "event-id",
                new DataIntegrityViolationException("duplicate")));

    // when & then
    assertThatCode(() -> listener.handleSeatHoldFailed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("일시적(인프라) 예외가 발생하면 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT 위임)")
  void handleSeatHoldFailedRethrowsTransientFailure() {
    // given
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, SeatHoldFailedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new RuntimeException("DB 일시 장애")).given(bookingCancelUseCase).execute(BOOKING_ID);

    // when & then
    assertThatThrownBy(() -> listener.handleSeatHoldFailed(envelope, acknowledgment))
        .isInstanceOf(RuntimeException.class);

    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("영구(비즈니스) 예외가 발생하면 예외를 전파하지 않고 오프셋을 커밋한다")
  void handleSeatHoldFailedAcksPermanentFailure() {
    // given: 이미 취소된 예매 등 상태충돌은 영구 실패로 분류되어 커밋된다
    final DomainEventEnvelope envelope = envelope();
    given(jsonConverter.deserialize(PAYLOAD, SeatHoldFailedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new BusinessException(ErrorStatus.BOOKING_CANCEL_NOT_ALLOWED))
        .given(bookingCancelUseCase)
        .execute(BOOKING_ID);

    // when & then
    assertThatCode(() -> listener.handleSeatHoldFailed(envelope, acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }
}
