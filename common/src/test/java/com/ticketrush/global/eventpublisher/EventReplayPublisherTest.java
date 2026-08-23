package com.ticketrush.global.eventpublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.payment.event.RefundFailedEvent;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventReplayPublisherTest {

  private static final String FIXED_EVENT_ID = "22222222-2222-2222-2222-222222222222";

  @InjectMocks private EventReplayPublisher eventReplayPublisher;

  @Mock private EventMessageSender eventMessageSender;
  @Mock private JsonConverter jsonConverter;

  private RefundFailedEvent event() {
    return new RefundFailedEvent(100L, null, "환불 실패", LocalDateTime.of(2026, 8, 13, 10, 0));
  }

  @Test
  @DisplayName("성공: 넘겨받은 eventId를 그대로 봉투에 실어 발행한다")
  void republish_preserves_given_event_id() {
    // given: 재발행의 핵심은 같은 이벤트가 같은 식별자를 갖는 것이다. 새로 만들면 수신 측 Inbox가
    // 매번 중복으로 걸러내지 못해 재발행마다 트랜잭션이 다시 열린다.
    RefundFailedEvent event = event();
    given(jsonConverter.serialize(event)).willReturn("{\"booking_id\":100}");

    // when
    eventReplayPublisher.republish(event, FIXED_EVENT_ID);

    // then
    ArgumentCaptor<DomainEventEnvelope> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
    verify(eventMessageSender).send(any(), captor.capture());

    DomainEventEnvelope envelope = captor.getValue();
    assertThat(envelope.eventId()).isEqualTo(FIXED_EVENT_ID);
    assertThat(envelope.eventType()).isEqualTo(RefundFailedEvent.EVENT_NAME);
    assertThat(envelope.topic()).isEqualTo(RefundFailedEvent.TOPIC);
    assertThat(envelope.payload()).isEqualTo("{\"booking_id\":100}");
  }

  @Test
  @DisplayName("성공: 파티션 키로 이벤트의 key()를 넘긴다")
  void republish_uses_event_key_as_partition_key() {
    // given
    RefundFailedEvent event = event();
    given(jsonConverter.serialize(event)).willReturn("{\"booking_id\":100}");

    // when
    eventReplayPublisher.republish(event, FIXED_EVENT_ID);

    // then
    verify(eventMessageSender).toMessage(any(DomainEventEnvelope.class), eq("100"));
  }

  @Test
  @DisplayName("성공: 같은 seed는 항상 같은 eventId로, 다른 seed는 다른 eventId로 옮겨진다")
  void deterministic_event_id_is_stable_per_seed() {
    // when
    String first = EventReplayPublisher.deterministicEventId("refund-failure-signal:7");
    String again = EventReplayPublisher.deterministicEventId("refund-failure-signal:7");
    String other = EventReplayPublisher.deterministicEventId("refund-failure-signal:8");

    // then
    assertThat(first).isEqualTo(again);
    assertThat(first).isNotEqualTo(other);
  }

  @Test
  @DisplayName("성공: 생성한 eventId는 inbox.event_id(varchar 36)에 들어가는 UUID 형식이다")
  void deterministic_event_id_fits_inbox_column() {
    // when
    String eventId = EventReplayPublisher.deterministicEventId("refund-failure-signal:7");

    // then: 컬럼이 varchar(36)이라 형식이 어긋나면 재발행이 통째로 저장 실패한다.
    assertThat(eventId).hasSize(36);
    assertThat(UUID.fromString(eventId)).isNotNull();
  }
}
