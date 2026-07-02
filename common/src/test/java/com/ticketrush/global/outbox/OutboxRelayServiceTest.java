package com.ticketrush.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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

@ExtendWith(MockitoExtension.class)
class OutboxRelayServiceTest {

  @InjectMocks private OutboxRelayService outboxRelayService;

  @Mock private OutboxRepository outboxRepository;
  @Mock private KafkaTemplate<String, DomainEventEnvelope> kafkaTemplate;
  @Mock private OutboxProperties outboxProperties;

  private OutboxEntity pendingRow(String eventId) {
    DomainEventEnvelope envelope =
        new DomainEventEnvelope(
            eventId,
            "BookingCreatedEvent",
            Instant.parse("2026-05-27T06:00:00Z"),
            "booking-created-topic",
            "{\"booking_id\":100}",
            "trace-1");
    return OutboxEntity.from(envelope, "Booking", "100", "100");
  }

  @Test
  @DisplayName("성공: 발행에 성공하면 SENT로 전이하고 eventId를 보존해 전송한다")
  @SuppressWarnings("unchecked")
  void relayBatch_marks_sent_on_success() {
    // given
    OutboxEntity row = pendingRow("evt-1");
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
    assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
    assertThat(row.getPublishedAt()).isNotNull();

    ArgumentCaptor<Message<DomainEventEnvelope>> captor = ArgumentCaptor.forClass(Message.class);
    verify(kafkaTemplate).send(captor.capture());
    Message<DomainEventEnvelope> sent = captor.getValue();
    assertThat(sent.getPayload().eventId()).isEqualTo("evt-1"); // eventId 보존
    assertThat(sent.getHeaders().get(KafkaHeaders.TOPIC)).isEqualTo("booking-created-topic");
    assertThat(sent.getHeaders().get(KafkaHeaders.KEY)).isEqualTo("100");
  }

  @Test
  @DisplayName("실패: 발행에 실패하면 FAILED로 전이하고 retryCount 증가·사유를 기록한다")
  @SuppressWarnings("unchecked")
  void relayBatch_marks_failed_on_error() {
    // given
    OutboxEntity row = pendingRow("evt-2");
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
    assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
    assertThat(row.getRetryCount()).isEqualTo(1);
    assertThat(row.getLastError()).contains("kaboom");
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
}
