package com.ticketrush.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.event.DomainEventEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

  /**
   * 릴레이 조회 스텁. 릴레이는 (aggregateType, status) 조합별 등치 조회로 쪼개 부르므로(#483) 상태별로 따로 스텁한다. {@code IN} 한 방이던
   * 시절처럼 두 상태에 같은 목록을 물리면 같은 행이 병합 목록에 중복으로 들어가 테스트가 실제 동작과 어긋난다.
   */
  private void givenRelayTargets(List<OutboxEntity> pending, List<OutboxEntity> failed) {
    given(outboxRepository.findOldestRelayTargets(eq("Booking"), eq("PENDING"), anyInt()))
        .willReturn(pending);
    given(outboxRepository.findOldestRelayTargets(eq("Booking"), eq("FAILED"), anyInt()))
        .willReturn(failed);
  }

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
    givenRelayTargets(List.of(row), List.of());
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
    givenRelayTargets(List.of(row), List.of());
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
    given(outboxProperties.getAggregateTypes()).willReturn(List.of("Booking"));
    given(outboxProperties.getBatchSize()).willReturn(100);
    givenRelayTargets(List.of(row), List.of());
    CompletableFuture<SendResult<String, DomainEventEnvelope>> pending = new CompletableFuture<>();
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
  @DisplayName("상태 전이가 실패해도 in-flight를 풀어 다음 폴링이 다시 발행한다")
  @SuppressWarnings("unchecked")
  void relayBatch_releases_in_flight_when_status_transition_throws() {
    // given: markSuccess가 터진다 = 행이 PENDING으로 남는다. 여기서 잠가두면 영영 발행되지 않는다.
    OutboxEntity row = pendingRow(4L, "evt-4");
    given(outboxProperties.getAggregateTypes()).willReturn(List.of("Booking"));
    given(outboxProperties.getBatchSize()).willReturn(100);
    givenRelayTargets(List.of(row), List.of());
    given(kafkaTemplate.send(any(Message.class)))
        .willReturn(
            CompletableFuture.completedFuture((SendResult<String, DomainEventEnvelope>) null));
    willThrow(new IllegalStateException("db down")).given(outboxStatusUpdater).markSuccess(4L);

    // when
    outboxRelayService.relayBatch();
    outboxRelayService.relayBatch();

    // then: 잠기지 않았으므로 두 번째 폴링도 발행한다
    verify(kafkaTemplate, times(2)).send(any(Message.class));
  }

  @Test
  @DisplayName("send가 동기 예외를 던지면 실패로 기록하고 같은 배치의 뒤쪽 행을 계속 발행한다")
  @SuppressWarnings("unchecked")
  void relayBatch_records_failure_and_continues_when_send_throws_synchronously() {
    // given: 앞 행이 poison. 재던지면 뒤쪽 행이 영영 발행되지 않는다(head-of-line 블록).
    OutboxEntity poison = pendingRow(5L, "evt-5");
    OutboxEntity healthy = pendingRow(6L, "evt-6");
    given(outboxProperties.getAggregateTypes()).willReturn(List.of("Booking"));
    given(outboxProperties.getBatchSize()).willReturn(100);
    givenRelayTargets(List.of(poison, healthy), List.of());
    given(kafkaTemplate.send(any(Message.class)))
        .willThrow(new IllegalArgumentException("serialization boom"))
        .willReturn(
            CompletableFuture.completedFuture((SendResult<String, DomainEventEnvelope>) null));

    // when
    outboxRelayService.relayBatch();

    // then: poison은 실패로 기록되고(retryCount 상승 → maxRetries 초과 시 DEAD), 뒤쪽 행은 발행된다
    verify(outboxStatusUpdater)
        .markFail(eq(5L), argThat(msg -> msg.contains("serialization boom")));
    verify(outboxStatusUpdater).markSuccess(6L);
    verify(kafkaTemplate, times(2)).send(any(Message.class));
  }

  @Test
  @DisplayName("콜백이 유실돼 오래 남은 in-flight 표시는 폴링이 걷어내 재발행을 허용한다")
  @SuppressWarnings("unchecked")
  void relayBatch_sweeps_stale_in_flight_entries() {
    // given: 콜백이 영영 오지 않는 발행. 청소가 없으면 이 행은 조회 윈도의 슬롯을 영구 점유한다.
    OutboxEntity row = pendingRow(7L, "evt-7");
    given(outboxProperties.getAggregateTypes()).willReturn(List.of("Booking"));
    given(outboxProperties.getBatchSize()).willReturn(100);
    givenRelayTargets(List.of(row), List.of());
    given(kafkaTemplate.send(any(Message.class))).willReturn(new CompletableFuture<>());

    outboxRelayService.relayBatch();
    verify(kafkaTemplate, times(1)).send(any(Message.class));

    // when: 유효기간(180초)을 넘긴 상태로 되돌린다
    Map<Long, Long> inFlight =
        (Map<Long, Long>) ReflectionTestUtils.getField(outboxRelayService, "inFlight");
    inFlight.replaceAll((id, at) -> at - 200_000L);
    outboxRelayService.relayBatch();

    // then
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
    verify(outboxRepository, never()).findOldestRelayTargets(any(), any(), anyInt());
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
    verify(outboxRepository, never()).findOldestRelayTargets(any(), any(), anyInt());
    verify(kafkaTemplate, never()).send(any(Message.class));
  }

  @Test
  @DisplayName("상태별로 쪼개 조회해도 두 상태를 합쳐 오래된 순으로 batchSize만큼만 발행한다")
  @SuppressWarnings("unchecked")
  void relayBatch_merges_per_status_results_in_id_order() {
    // given: 조합별 등치 조회로 쪼갠 뒤에도 '전체 기준 오래된 순'이 유지돼야 한다(#483).
    given(outboxProperties.getAggregateTypes()).willReturn(List.of("Booking"));
    given(outboxProperties.getBatchSize()).willReturn(3);
    givenRelayTargets(
        List.of(pendingRow(3L, "evt-3"), pendingRow(5L, "evt-5")),
        List.of(pendingRow(1L, "evt-1"), pendingRow(4L, "evt-4")));
    given(kafkaTemplate.send(any(Message.class)))
        .willReturn(
            CompletableFuture.completedFuture((SendResult<String, DomainEventEnvelope>) null));

    // when
    outboxRelayService.relayBatch();

    // then: 병합 정렬 결과 1,3,4,5 중 앞의 3건만 나가고 5번은 다음 폴링으로 밀린다
    ArgumentCaptor<Message<DomainEventEnvelope>> captor = ArgumentCaptor.forClass(Message.class);
    verify(kafkaTemplate, times(3)).send(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(message -> message.getPayload().eventId())
        .containsExactly("evt-1", "evt-3", "evt-4");
  }

  @Test
  @DisplayName("in-flight 게이지가 콜백 대기 중인 건수를 노출한다")
  @SuppressWarnings("unchecked")
  void inFlightGauge_exposes_pending_callback_count() {
    // given: 콜백이 오지 않는 발행 = backlog에는 PENDING으로 잡히지만 실제로는 대기 중인 상태
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OutboxRelayService service =
        new OutboxRelayService(
            outboxRepository, kafkaTemplate, outboxProperties, outboxStatusUpdater, registry);
    service.registerGauges();
    given(outboxProperties.getAggregateTypes()).willReturn(List.of("Booking"));
    given(outboxProperties.getBatchSize()).willReturn(100);
    givenRelayTargets(List.of(pendingRow(8L, "evt-8"), pendingRow(9L, "evt-9")), List.of());
    given(kafkaTemplate.send(any(Message.class))).willReturn(new CompletableFuture<>());

    assertThat(registry.get(MetricNames.OUTBOX_IN_FLIGHT).gauge().value()).isZero();

    // when
    service.relayBatch();

    // then
    assertThat(registry.get(MetricNames.OUTBOX_IN_FLIGHT).gauge().value()).isEqualTo(2.0);
  }
}
