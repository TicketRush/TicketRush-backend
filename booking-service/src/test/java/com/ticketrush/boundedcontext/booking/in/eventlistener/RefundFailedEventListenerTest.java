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

import com.ticketrush.boundedcontext.booking.app.usecase.BookingMarkRefundFailedUseCase;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.payment.event.RefundFailedEvent;
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
class RefundFailedEventListenerTest {

  @InjectMocks private RefundFailedEventListener listener;

  @Mock private BookingMarkRefundFailedUseCase bookingMarkRefundFailedUseCase;

  @Mock private JsonConverter jsonConverter;

  @Mock private InboxService inboxService;

  @Mock private Acknowledgment acknowledgment;

  private static final String PAYLOAD = "payload";
  private static final Long BOOKING_ID = 10L;

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        "event-id",
        RefundFailedEvent.EVENT_NAME,
        Instant.now(),
        RefundFailedEvent.TOPIC,
        PAYLOAD,
        null);
  }

  private RefundFailedEvent event() {
    return new RefundFailedEvent(
        BOOKING_ID, "BOOK-1234", "PG 환불 처리 실패", LocalDateTime.of(2026, 5, 22, 10, 30));
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
  @DisplayName("최초 수신이면 예매를 REFUND_FAILED로 보상하고 오프셋을 커밋한다")
  void handleRefundFailed() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, RefundFailedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();

    // when
    listener.handleRefundFailed(envelope(), acknowledgment);

    // then
    verify(bookingMarkRefundFailedUseCase).execute(BOOKING_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이미 처리된 이벤트(inbox 중복)면 보상을 실행하지 않고 오프셋만 커밋한다")
  void handleRefundFailedSkipsDuplicate() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, RefundFailedEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.BOOKING),
                any(DomainEventEnvelope.class),
                any(Runnable.class)))
        .willReturn(false);

    // when
    listener.handleRefundFailed(envelope(), acknowledgment);

    // then
    verify(bookingMarkRefundFailedUseCase, never()).execute(anyLong());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("동시 중복 수신으로 inbox unique가 경합하면(DuplicateEventException) 예외를 전파하지 않고 오프셋을 커밋한다")
  void handleRefundFailedAcksOnInboxRace() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, RefundFailedEvent.class)).willReturn(event());
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
    assertThatCode(() -> listener.handleRefundFailed(envelope(), acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("일시적(인프라) 예외가 발생하면 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT 위임)")
  void handleRefundFailedRethrowsTransientFailure() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, RefundFailedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new RuntimeException("DB 일시 장애"))
        .given(bookingMarkRefundFailedUseCase)
        .execute(BOOKING_ID);

    // when & then
    assertThatThrownBy(() -> listener.handleRefundFailed(envelope(), acknowledgment))
        .isInstanceOf(RuntimeException.class);

    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("영구(비즈니스) 예외가 발생하면 예외를 전파하지 않고 오프셋을 커밋한다")
  void handleRefundFailedAcksPermanentFailure() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, RefundFailedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new BusinessException(ErrorStatus.BOOKING_NOT_FOUND))
        .given(bookingMarkRefundFailedUseCase)
        .execute(BOOKING_ID);

    // when & then
    assertThatCode(() -> listener.handleRefundFailed(envelope(), acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }
}
