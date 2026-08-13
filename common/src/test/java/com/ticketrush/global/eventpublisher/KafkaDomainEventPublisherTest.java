package com.ticketrush.global.eventpublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.payment.event.RefundFailedEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class KafkaDomainEventPublisherTest {

  @InjectMocks private KafkaDomainEventPublisher kafkaDomainEventPublisher;

  @Mock private EventMessageSender eventMessageSender;
  @Mock private JsonConverter jsonConverter;

  @AfterEach
  void tearDown() {
    // 정적 ThreadLocal 상태가 다른 테스트로 새지 않도록 정리한다.
    TransactionSynchronizationManager.setActualTransactionActive(false);
    // 활성화하지 않은 상태에서 clear하면 IllegalStateException이므로 활성 여부를 먼저 본다.
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private RefundFailedEvent event() {
    return new RefundFailedEvent(100L, "BOOK-1234", "환불 실패", LocalDateTime.of(2026, 8, 13, 10, 0));
  }

  @Test
  @DisplayName("성공: 활성 트랜잭션이 없으면 즉시 발행한다")
  void publish_sends_immediately_without_transaction() {
    // given
    RefundFailedEvent event = event();
    given(jsonConverter.serialize(event)).willReturn("{\"booking_id\":100}");

    // when
    kafkaDomainEventPublisher.publish(event);

    // then
    ArgumentCaptor<DomainEventEnvelope> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
    verify(eventMessageSender).send(any(), captor.capture());
    assertThat(captor.getValue().eventType()).isEqualTo(RefundFailedEvent.EVENT_NAME);
    assertThat(captor.getValue().topic()).isEqualTo(RefundFailedEvent.TOPIC);
  }

  @Test
  @DisplayName("성공: 활성 트랜잭션이 있으면 커밋 전에는 발행하지 않고, 커밋 후에 발행한다")
  void publish_defers_until_commit_inside_transaction() {
    // given: 커밋 전에 나가면 롤백된 변경에 대한 이벤트가 새어나간다(#138).
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    RefundFailedEvent event = event();
    given(jsonConverter.serialize(event)).willReturn("{\"booking_id\":100}");

    // when
    kafkaDomainEventPublisher.publish(event);

    // then: 아직 나가지 않았다.
    verify(eventMessageSender, never()).send(any(), any());
    assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

    // when: 등록된 훅을 실제로 커밋시킨다. 여기까지 하지 않으면 훅 본문이 비어도 테스트가 통과한다.
    TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

    // then
    ArgumentCaptor<DomainEventEnvelope> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
    verify(eventMessageSender).send(any(), captor.capture());
    assertThat(captor.getValue().eventType()).isEqualTo(RefundFailedEvent.EVENT_NAME);
  }

  @Test
  @DisplayName("성공: 파티션 키로 이벤트의 key()를 넘긴다")
  void publish_uses_event_key_as_partition_key() {
    // given
    RefundFailedEvent event = event();
    given(jsonConverter.serialize(event)).willReturn("{\"booking_id\":100}");

    // when
    kafkaDomainEventPublisher.publish(event);

    // then: 같은 예매의 이벤트가 한 파티션에 모여야 순서가 보장된다.
    verify(eventMessageSender).toMessage(any(DomainEventEnvelope.class), eq("100"));
  }

  @Test
  @DisplayName("성공: 신규 발행은 매번 다른 eventId를 만든다")
  void publish_generates_new_event_id_each_time() {
    // given: 재발행(EventReplayPublisher)과 달리 신규 발행은 randomUUID다.
    RefundFailedEvent event = event();
    given(jsonConverter.serialize(event)).willReturn("{\"booking_id\":100}");

    // when
    kafkaDomainEventPublisher.publish(event);
    kafkaDomainEventPublisher.publish(event);

    // then
    ArgumentCaptor<DomainEventEnvelope> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
    verify(eventMessageSender, times(2)).send(any(), captor.capture());
    assertThat(captor.getAllValues().get(0).eventId())
        .isNotEqualTo(captor.getAllValues().get(1).eventId());
  }
}
