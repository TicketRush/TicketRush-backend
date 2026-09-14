package com.ticketrush.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.config.JacksonConfig;
import com.ticketrush.global.event.DomainEventEnvelope;
import com.ticketrush.global.json.JsonConverter;
import com.ticketrush.shared.booking.event.BookingExpiredEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@ResourceLock("java.util.TimeZone.default")
class UtcOutboxReplayTest {

  @ParameterizedTest
  @ValueSource(strings = {"UTC", "Asia/Seoul"})
  @DisplayName("UTC 출처의 구형 페이로드를 재발행해도 ID·원문·시점·외피 정밀도를 보존한다")
  @SuppressWarnings("unchecked")
  void replay_preserves_known_utc_payload_and_audited_envelope(String zone) {
    TimeZone original = TimeZone.getDefault();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      assertThat(TimeZone.getDefault().getID()).isEqualTo(zone);
      JsonMapper.Builder builder = JsonMapper.builder();
      new JacksonConfig().jacksonCustomizer().customize(builder);
      JsonConverter converter = new JsonConverter(builder.build());
      String legacyPayload = "{\"booking_id\":1,\"expired_at\":\"2026-09-14 05:00:00\"}";
      BookingExpiredEvent restored =
          converter.deserialize(legacyPayload, BookingExpiredEvent.class);
      assertThat(restored.expiredAt().toInstant(ZoneOffset.UTC))
          .isEqualTo(Instant.parse("2026-09-14T05:00:00Z"));
      assertThat(converter.serialize(restored)).isEqualTo(legacyPayload);
      LocalDateTime auditedUtc = LocalDateTime.parse("2026-09-14T05:00:01.123456789");
      DomainEventEnvelope envelope =
          new DomainEventEnvelope(
              "legacy-id",
              restored.eventName(),
              auditedUtc.toInstant(ZoneOffset.UTC),
              restored.topic(),
              legacyPayload,
              "trace-id");
      OutboxEntity row = OutboxEntity.from(envelope, "Booking", "1", "1");
      ReflectionTestUtils.setField(row, "id", 1L);
      ReflectionTestUtils.setField(row, "createdAt", auditedUtc);
      OutboxRepository repository = mock(OutboxRepository.class);
      OutboxProperties properties = new OutboxProperties();
      properties.setAggregateTypes(List.of("Booking"));
      KafkaTemplate<String, DomainEventEnvelope> kafka = mock(KafkaTemplate.class);
      final OutboxStatusUpdater updater = mock(OutboxStatusUpdater.class);
      given(repository.findOldestRelayTargets(eq("Booking"), eq("PENDING"), anyInt()))
          .willReturn(List.of(row));
      given(repository.findOldestRelayTargets(eq("Booking"), eq("FAILED"), anyInt()))
          .willReturn(List.of());
      given(kafka.send(any(Message.class))).willReturn(CompletableFuture.completedFuture(null));

      new OutboxRelayService(repository, kafka, properties, updater, registry).relayBatch();

      ArgumentCaptor<Message<DomainEventEnvelope>> captor = ArgumentCaptor.forClass(Message.class);
      verify(kafka).send(captor.capture());
      DomainEventEnvelope sent = captor.getValue().getPayload();
      assertThat(sent.eventId()).isEqualTo("legacy-id");
      assertThat(sent.payload()).isEqualTo(legacyPayload);
      assertThat(sent.createdAt()).isEqualTo(auditedUtc.toInstant(ZoneOffset.UTC));
      assertThat(converter.deserialize(sent.payload(), BookingExpiredEvent.class))
          .isEqualTo(restored);
      verify(updater).markSuccess(1L);
    } finally {
      TimeZone.setDefault(original);
    }
  }
}
