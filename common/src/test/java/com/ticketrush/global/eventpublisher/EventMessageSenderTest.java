package com.ticketrush.global.eventpublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.event.DomainEventEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

@ExtendWith(MockitoExtension.class)
class EventMessageSenderTest {

  private static final String TOPIC = "refund-failed-topic";
  private static final String EVENT_ID = "11111111-1111-1111-1111-111111111111";

  @Mock private KafkaTemplate<String, DomainEventEnvelope> kafkaTemplate;

  private SimpleMeterRegistry meterRegistry;
  private EventMessageSender eventMessageSender;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    eventMessageSender = new EventMessageSender(kafkaTemplate, meterRegistry);
  }

  private DomainEventEnvelope envelope() {
    return new DomainEventEnvelope(
        EVENT_ID, "RefundFailed", Instant.now(), TOPIC, "{\"booking_id\":1}", "trace-1");
  }

  @Test
  @DisplayName("성공: 봉투와 파티션 키로 토픽·키·eventType·eventId 헤더를 채운다")
  void toMessage_sets_routing_headers() {
    // given
    DomainEventEnvelope envelope = envelope();

    // when
    Message<DomainEventEnvelope> message = eventMessageSender.toMessage(envelope, "42");

    // then: 이 네 헤더가 수신 측 라우팅과 멱등의 기준이라 하나라도 빠지면 조용히 어긋난다.
    assertThat(message.getHeaders().get(KafkaHeaders.TOPIC)).isEqualTo(TOPIC);
    assertThat(message.getHeaders().get(KafkaHeaders.KEY)).isEqualTo("42");
    assertThat(message.getHeaders().get("eventType")).isEqualTo("RefundFailed");
    assertThat(message.getHeaders().get("eventId")).isEqualTo(EVENT_ID);
    assertThat(message.getPayload()).isEqualTo(envelope);
  }

  @Test
  @DisplayName("성공: 발행이 성공하면 실패 카운터가 올라가지 않는다")
  void send_does_not_count_on_success() {
    // given
    given(kafkaTemplate.send(any(Message.class)))
        .willReturn(CompletableFuture.completedFuture(null));
    DomainEventEnvelope envelope = envelope();

    // when
    eventMessageSender.send(eventMessageSender.toMessage(envelope, "42"), envelope);

    // then
    assertThat(meterRegistry.find(MetricNames.EVENT_PUBLISH_FAILED).counter()).isNull();
  }

  @Test
  @DisplayName("성공: 발행이 실패하면 토픽 태그와 함께 실패 카운터를 올린다")
  void send_counts_failure_with_topic_tag() {
    // given: 발행 실패는 호출자에게 전파되지 않고 콜백에서 끝난다. 이 카운터가 유일한 관측 축이다(#574).
    given(kafkaTemplate.send(any(Message.class)))
        .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
    DomainEventEnvelope envelope = envelope();

    // when
    eventMessageSender.send(eventMessageSender.toMessage(envelope, "42"), envelope);

    // then
    assertThat(
            meterRegistry
                .get(MetricNames.EVENT_PUBLISH_FAILED)
                .tag(MetricNames.TAG_TOPIC, TOPIC)
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("성공: 발행이 실패해도 호출자에게 예외를 전파하지 않는다")
  void send_does_not_propagate_failure() {
    // given
    given(kafkaTemplate.send(any(Message.class)))
        .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
    DomainEventEnvelope envelope = envelope();
    Message<DomainEventEnvelope> message = eventMessageSender.toMessage(envelope, "42");

    // when & then: 예외가 새면 발행 실패가 도메인 흐름(보상·취소)을 깨뜨린다.
    eventMessageSender.send(message, envelope);
  }
}
