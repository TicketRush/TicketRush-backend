package com.ticketrush.boundedcontext.seat.in.eventlistener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.app.usecase.SeatReleaseSoldSeatUseCase;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.payment.event.PaymentCanceledEvent;
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
class PaymentCanceledEventListenerTest {

  @InjectMocks private PaymentCanceledEventListener listener;

  @Mock private SeatReleaseSoldSeatUseCase seatReleaseSoldSeatUseCase;

  @Mock private JsonConverter jsonConverter;

  @Mock private InboxService inboxService;

  @Mock private Acknowledgment acknowledgment;

  private static final String PAYLOAD = "payload";
  private static final Long SEAT_ID = 3L;

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        "event-id",
        PaymentCanceledEvent.EVENT_NAME,
        Instant.now(),
        PaymentCanceledEvent.TOPIC,
        PAYLOAD,
        null);
  }

  private PaymentCanceledEvent event() {
    return new PaymentCanceledEvent(
        1L, 10L, SEAT_ID, 5L, 50000L, "단순 변심", LocalDateTime.of(2026, 5, 22, 10, 30));
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
  void handlePaymentCanceled() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, PaymentCanceledEvent.class)).willReturn(event());
    givenInboxProcessesFirst();

    // when
    listener.handlePaymentCanceled(envelope(), acknowledgment);

    // then
    verify(seatReleaseSoldSeatUseCase).execute(SEAT_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이미 처리된 이벤트(inbox 중복)면 좌석 반환을 실행하지 않고 오프셋만 커밋한다")
  void handlePaymentCanceledSkipsDuplicate() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, PaymentCanceledEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.SEAT), any(DomainEventEnvelope.class), any(Runnable.class)))
        .willReturn(false);

    // when
    listener.handlePaymentCanceled(envelope(), acknowledgment);

    // then
    verify(seatReleaseSoldSeatUseCase, never()).execute(anyLong());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("동시 중복 수신으로 inbox unique가 경합하면(DuplicateEventException) 예외를 전파하지 않고 오프셋을 커밋한다")
  void handlePaymentCanceledAcksOnInboxRace() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, PaymentCanceledEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.SEAT), any(DomainEventEnvelope.class), any(Runnable.class)))
        .willThrow(
            new DuplicateEventException(
                KafkaConsumerGroup.SEAT,
                "event-id",
                new DataIntegrityViolationException("duplicate")));

    // when & then
    assertThatCode(() -> listener.handlePaymentCanceled(envelope(), acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("일시적(인프라) 예외가 발생하면 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT 위임)")
  void handlePaymentCanceledRethrowsTransientFailure() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, PaymentCanceledEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new RuntimeException("DB 일시 장애")).given(seatReleaseSoldSeatUseCase).execute(SEAT_ID);

    // when & then
    assertThatThrownBy(() -> listener.handlePaymentCanceled(envelope(), acknowledgment))
        .isInstanceOf(RuntimeException.class);

    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("예상된 상태충돌(화이트리스트 409)은 warn 후 오프셋을 커밋한다")
  void handlePaymentCanceledAcksExpectedConflict() {
    // given: SEAT_NOT_AVAILABLE(409)은 EXPECTED_CONFLICTS라 warn+ack 분기로 간다
    given(jsonConverter.deserialize(PAYLOAD, PaymentCanceledEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new BusinessException(ErrorStatus.SEAT_NOT_AVAILABLE))
        .given(seatReleaseSoldSeatUseCase)
        .execute(SEAT_ID);

    // when & then
    assertThatCode(() -> listener.handlePaymentCanceled(envelope(), acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("영구(비즈니스) 예외가 발생하면 예외를 전파하지 않고 오프셋을 커밋한다")
  void handlePaymentCanceledAcksPermanentFailure() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, PaymentCanceledEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new BusinessException(ErrorStatus.SEAT_NOT_FOUND))
        .given(seatReleaseSoldSeatUseCase)
        .execute(SEAT_ID);

    // when & then
    assertThatCode(() -> listener.handlePaymentCanceled(envelope(), acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }
}
