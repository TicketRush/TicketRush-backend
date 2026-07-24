package com.ticketrush.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.event.DomainEventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OutboxRelayServiceTest {

  @InjectMocks private OutboxRelayService outboxRelayService;

  @Mock private OutboxRepository outboxRepository;
  @Mock private KafkaTemplate<String, DomainEventEnvelope> kafkaTemplate;
  @Mock private OutboxProperties outboxProperties;
  @Mock private OutboxStatusUpdater outboxStatusUpdater;

  private OutboxEntity pendingRow(Long id, String eventId) {
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            eventId,
            "BookingCreatedEvent",
            Instant.parse("2026-05-27T06:00:00Z"),
            "booking-created-topic",
            "{\"booking_id\":100}",
            "trace-1");
    OutboxEntity row = OutboxEntity.from(envelope, "Booking", "100", "100");
    ReflectionTestUtils.setField(row, "id", id);
    return row;
  }

  @Test
  @DisplayName("성공: 발행 완료 콜백에서 markSuccess를 호출하고 eventId를 보존해 전송한다")
  @SuppressWarnings("unchecked")
  void relayBatch_marks_success_via_callback() {
    // given
    OutboxEntity row = pendingRow(1L, "evt-1");
    given(outboxProperties.getAggregateTypes()).willReturn(List.of("Booking"));
    given(outboxProperties.getBatchSize()).willReturn(100);
    given(
            outboxRepository.findByAggregateTypeInAndStatusInOrderByIdAsc(
                eq(List.of("Booking")),
                eq(List.of(OutboxStatus.PENDING, OutboxStatus.FAILED)),
                any(Pageable.class)))
        .willReturn(List.of(row));
    given(kafkaTemplate.send(any(Message.class)))
        .willReturn(
            CompletableFuture.completedFuture((SendResult<String, DomainEventEnvelope>) null));

    // when
    outboxRelayService.relayBatch();

    // then
    verify(outboxStatusUpdater).markSuccess(1L);

    ArgumentCaptor<Message<DomainEventEnvelope>> captor = ArgumentCaptor.forClass(Message.class);
    verify(kafkaTemplate).send(captor.capture());
    Message<DomainEventEnvelope> sent = captor.getValue();
    assertThat(sent.getPayload().eventId()).isEqualTo("evt-1"); // eventId 보존
    assertThat(sent.getHeaders().get(KafkaHeaders.TOPIC)).isEqualTo("booking-created-topic");
    assertThat(sent.getHeaders().get(KafkaHeaders.KEY)).isEqualTo("100");
  }

  @Test
  @DisplayName("실패: 발행 실패 콜백에서 근본 원인을 담아 markFail을 호출한다")
  @SuppressWarnings("unchecked")
  void relayBatch_marks_fail_via_callback() {
    // given
    OutboxEntity row = pendingRow(2L, "evt-2");
    given(outboxProperties.getAggregateTypes()).willReturn(List.of("Booking"));
    given(outboxProperties.getBatchSize()).willReturn(100);
    given(
            outboxRepository.findByAggregateTypeInAndStatusInOrderByIdAsc(
                any(), any(), any(Pageable.class)))
        .willReturn(List.of(row));
    given(kafkaTemplate.send(any(Message.class)))
        .willReturn(CompletableFuture.failedFuture(new RuntimeException("kaboom")));

    // when
    outboxRelayService.relayBatch();

    // then
    verify(outboxStatusUpdater).markFail(eq(2L), argThat(msg -> msg.contains("kaboom")));
    verify(outboxStatusUpdater, never()).markSuccess(any());
  }

  @Test
  @DisplayName("발행 콜백이 아직 안 온 행은 다음 폴링에서 다시 발행하지 않는다")
  @SuppressWarnings("unchecked")
  void relayBatch_does_not_republish_row_whose_callback_has_not_completed() {
    // given: 콜백이 아직 완료되지 않은 발행 = 조회에는 여전히 PENDING 으로 잡히는 상태
    OutboxEntity row = pendingRow(3L, "evt-3");
    CompletableFuture<SendResult<String, DomainEventEnvelope>> pending = new CompletableFuture<>();
    given(outboxProperties.getAggregateTypes()).willReturn(List.of("Booking"));
    given(outboxProperties.getBatchSize()).willReturn(100);
    given(
            outboxRepository.findByAggregateTypeInAndStatusInOrderByIdAsc(
                any(), any(), any(Pageable.class)))
        .willReturn(List.of(row));
    given(kafkaTemplate.send(any(Message.class))).willReturn(pending);

    // when: 콜백이 오기 전에 폴링이 세 번 더 돈다 (#344 실측의 3.09배 증폭이 나온 상황)
    outboxRelayService.relayBatch();
    outboxRelayService.relayBatch();
    outboxRelayService.relayBatch();
    outboxRelayService.relayBatch();

    // then: 발행은 첫 폴링의 1회뿐
    verify(kafkaTemplate, times(1)).send(any(Message.class));

    // 콜백이 도착해 잠금이 풀리면 다시 집을 수 있어야 한다.
    // 전이가 실패해 행이 PENDING 으로 남는 경우까지 영영 막아버리면 안 되기 때문이다.
    pending.complete(null);
    verify(outboxStatusUpdater).markSuccess(3L);

    outboxRelayService.relayBatch();
    verify(kafkaTemplate, times(2)).send(any(Message.class));
  }

  @Test
  @DisplayName("소유 aggregateType이 비어 있으면 아무 것도 조회·발행하지 않는다")
  void relayBatch_does_nothing_when_no_owned_aggregate_types() {
    // given
    given(outboxProperties.getAggregateTypes()).willReturn(List.of());

    // when
    outboxRelayService.relayBatch();

    // then
    verify(outboxRepository, never())
        .findByAggregateTypeInAndStatusInOrderByIdAsc(any(), any(), any());
    verify(kafkaTemplate, never()).send(any(Message.class));
  }

  @Test
  @DisplayName("batchSize가 1 미만이면 PageRequest 예외 대신 조회를 건너뛴다")
  void relayBatch_skips_when_batch_size_is_not_positive() {
    // given
    given(outboxProperties.getAggregateTypes()).willReturn(List.of("Booking"));
    given(outboxProperties.getBatchSize()).willReturn(0);

    // when
    outboxRelayService.relayBatch();

    // then
    verify(outboxRepository, never())
        .findByAggregateTypeInAndStatusInOrderByIdAsc(any(), any(), any());
    verify(kafkaTemplate, never()).send(any(Message.class));
  }
}
