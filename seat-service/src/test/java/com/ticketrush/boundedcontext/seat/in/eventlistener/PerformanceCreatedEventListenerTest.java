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

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.event.KafkaConsumerGroup;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.inbox.DuplicateEventException;
import com.ticketrush.global.inbox.InboxService;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.shared.performance.event.PerformanceCreatedEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class PerformanceCreatedEventListenerTest {

  @InjectMocks private PerformanceCreatedEventListener listener;

  @Mock private SeatFacade seatFacade;

  @Mock private JsonConverter jsonConverter;

  @Mock private InboxService inboxService;

  @Mock private Acknowledgment acknowledgment;

  private static final Long PERFORMANCE_ID = 1L;
  private static final String PAYLOAD = "{\"performance_id\":1}";

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        "event-id", "PerformanceCreated", Instant.now(), "performance-events", PAYLOAD, null);
  }

  private PerformanceCreatedEvent event() {
    return new PerformanceCreatedEvent(
        PERFORMANCE_ID, "콘서트", 120, LocalDate.now(), LocalTime.of(19, 0), 50000L);
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
  @DisplayName("최초 수신이면 기본 좌석 생성을 요청하고 오프셋을 커밋한다")
  void handlePerformanceCreated() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, PerformanceCreatedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();

    // when
    listener.handlePerformanceCreated(envelope(), acknowledgment);

    // then
    verify(seatFacade).createDefaultSeats(PERFORMANCE_ID);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("공연 생성 이벤트가 아니면 좌석을 생성하지 않고 오프셋만 커밋한다")
  void handlePerformanceCreatedIgnoresOtherEventTypes() {
    // given: eventType 필터에서 걸러지므로 inbox/역직렬화까지 가지 않는다
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            "event-id", "PerformanceUpdated", Instant.now(), "performance-events", "{}", null);

    // when
    listener.handlePerformanceCreated(envelope, acknowledgment);

    // then
    verify(seatFacade, never()).createDefaultSeats(anyLong());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("이미 처리된 이벤트(inbox 중복)면 좌석 생성을 실행하지 않고 오프셋만 커밋한다")
  void handlePerformanceCreatedSkipsDuplicate() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, PerformanceCreatedEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.SEAT), any(DomainEventEnvelope.class), any(Runnable.class)))
        .willReturn(false);

    // when
    listener.handlePerformanceCreated(envelope(), acknowledgment);

    // then
    verify(seatFacade, never()).createDefaultSeats(anyLong());
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("동시 중복 수신으로 inbox unique가 경합하면(DIVE) 예외를 전파하지 않고 오프셋을 커밋한다")
  void handlePerformanceCreatedAcksOnInboxRace() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, PerformanceCreatedEvent.class)).willReturn(event());
    given(
            inboxService.runIfFirst(
                eq(KafkaConsumerGroup.SEAT), any(DomainEventEnvelope.class), any(Runnable.class)))
        .willThrow(
            new DuplicateEventException(
                KafkaConsumerGroup.SEAT,
                "event-id",
                new DataIntegrityViolationException("duplicate")));

    // when & then
    assertThatCode(() -> listener.handlePerformanceCreated(envelope(), acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("비즈니스 무결성 위반(DIVE)은 inbox 경합이 아니므로 삼키지 않고 전파한다(재시도→DLT 위임)")
  void handlePerformanceCreatedRethrowsBusinessDataIntegrityViolation() {
    // given: 비즈니스가 던진 DIVE는 DuplicateEventException이 아니므로 #269 일시 분기로 흘러 보존되어야 한다(유실 금지)
    given(jsonConverter.deserialize(PAYLOAD, PerformanceCreatedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new DataIntegrityViolationException("좌석 무결성 위반"))
        .given(seatFacade)
        .createDefaultSeats(PERFORMANCE_ID);

    // when & then
    assertThatThrownBy(() -> listener.handlePerformanceCreated(envelope(), acknowledgment))
        .isInstanceOf(DataIntegrityViolationException.class);

    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("일시적(인프라) 예외가 발생하면 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT 위임)")
  void handlePerformanceCreatedRethrowsTransientFailure() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, PerformanceCreatedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new RuntimeException("DB 일시 장애"))
        .given(seatFacade)
        .createDefaultSeats(PERFORMANCE_ID);

    // when & then
    assertThatThrownBy(() -> listener.handlePerformanceCreated(envelope(), acknowledgment))
        .isInstanceOf(RuntimeException.class);

    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("영구(비즈니스) 예외가 발생하면 예외를 전파하지 않고 오프셋을 커밋한다")
  void handlePerformanceCreatedAcksPermanentFailure() {
    // given
    given(jsonConverter.deserialize(PAYLOAD, PerformanceCreatedEvent.class)).willReturn(event());
    givenInboxProcessesFirst();
    willThrow(new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND))
        .given(seatFacade)
        .createDefaultSeats(PERFORMANCE_ID);

    // when & then
    assertThatCode(() -> listener.handlePerformanceCreated(envelope(), acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }
}
