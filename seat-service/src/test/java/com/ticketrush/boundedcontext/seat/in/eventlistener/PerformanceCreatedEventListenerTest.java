package com.ticketrush.boundedcontext.seat.in.eventlistener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.exception.BusinessException;
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
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class PerformanceCreatedEventListenerTest {

  @InjectMocks private PerformanceCreatedEventListener listener;

  @Mock private SeatFacade seatFacade;

  @Mock private JsonConverter jsonConverter;

  @Mock private Acknowledgment acknowledgment;

  @Test
  @DisplayName("공연 생성 이벤트를 수신하면 기본 좌석 생성을 요청하고 오프셋을 커밋한다")
  void handlePerformanceCreated() {
    // given
    Long performanceId = 1L;
    String payload = "{\"performance_id\":1}";
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            "event-id", "PerformanceCreated", Instant.now(), "performance-events", payload, null);
    PerformanceCreatedEvent event =
        new PerformanceCreatedEvent(
            performanceId, "콘서트", 120, LocalDate.now(), LocalTime.of(19, 0), 50000L);

    given(jsonConverter.deserialize(payload, PerformanceCreatedEvent.class)).willReturn(event);

    // when
    listener.handlePerformanceCreated(envelope, acknowledgment);

    // then
    verify(seatFacade).createDefaultSeats(performanceId);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("공연 생성 이벤트가 아니면 좌석을 생성하지 않고 오프셋만 커밋한다")
  void handlePerformanceCreatedIgnoresOtherEventTypes() {
    // given
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
  @DisplayName("일시적(인프라) 예외가 발생하면 예외를 전파하고 오프셋을 커밋하지 않는다(재시도→DLT 위임)")
  void handlePerformanceCreatedRethrowsTransientFailure() {
    // given
    Long performanceId = 1L;
    String payload = "{\"performance_id\":1}";
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            "event-id", "PerformanceCreated", Instant.now(), "performance-events", payload, null);
    PerformanceCreatedEvent event =
        new PerformanceCreatedEvent(
            performanceId, "콘서트", 120, LocalDate.now(), LocalTime.of(19, 0), 50000L);
    given(jsonConverter.deserialize(payload, PerformanceCreatedEvent.class)).willReturn(event);
    willThrow(new RuntimeException("DB 일시 장애")).given(seatFacade).createDefaultSeats(performanceId);

    // when & then
    assertThatThrownBy(() -> listener.handlePerformanceCreated(envelope, acknowledgment))
        .isInstanceOf(RuntimeException.class);

    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("영구(비즈니스) 예외가 발생하면 예외를 전파하지 않고 오프셋을 커밋한다")
  void handlePerformanceCreatedAcksPermanentFailure() {
    // given
    Long performanceId = 1L;
    String payload = "{\"performance_id\":1}";
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            "event-id", "PerformanceCreated", Instant.now(), "performance-events", payload, null);
    PerformanceCreatedEvent event =
        new PerformanceCreatedEvent(
            performanceId, "콘서트", 120, LocalDate.now(), LocalTime.of(19, 0), 50000L);
    given(jsonConverter.deserialize(payload, PerformanceCreatedEvent.class)).willReturn(event);
    willThrow(new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND))
        .given(seatFacade)
        .createDefaultSeats(performanceId);

    // when & then
    assertThatCode(() -> listener.handlePerformanceCreated(envelope, acknowledgment))
        .doesNotThrowAnyException();

    verify(acknowledgment).acknowledge();
  }
}
