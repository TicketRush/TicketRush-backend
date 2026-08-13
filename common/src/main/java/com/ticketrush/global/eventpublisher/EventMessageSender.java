package com.ticketrush.global.eventpublisher;

import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.event.DomainEventEnvelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 이벤트 봉투를 Kafka 메시지로 만들어 발행하는 공유 경로.
 *
 * <p>{@link KafkaDomainEventPublisher}(신규 발행)와 {@link EventReplayPublisher}(저장된 식별자로 재발행)가 같은 메시지
 * 규약을 써야 하므로 여기 한 곳에 모은다. 헤더 구성이 갈라지면 수신 측 라우팅·멱등이 조용히 어긋나는데, 컴파일은 통과하고 mock 테스트로도 잡히지 않는다.
 *
 * <p><b>아직 세 번째 생성 지점이 남아 있다.</b> {@code OutboxRelayService#toMessage} 가 outbox row 에서 같은 형태의 메시지를
 * 직접 만든다. 그쪽은 이 이슈(#574)의 범위 밖이라 건드리지 않았으므로, 규약이 여기로 완전히 단일화됐다고 읽으면 안 된다.
 *
 * <p><b>발행 실패는 여기서만 관측된다 (#574).</b> {@code send()}가 돌려주는 future의 예외는 호출자에게 전파되지 않고 콜백에서 끝나므로, 이
 * 카운터가 없으면 발행 유실은 로그 한 줄로 사라진다. 콜백은 프로듀서 IO 스레드에서 실행되므로 <b>여기서 외부 호출(알림 웹훅 등)을 해서는 안 된다</b> — 브로커
 * 장애로 실패가 쏟아질 때 IO 스레드가 그 호출에 묶여 장애를 키운다. 알림은 이 카운터를 읽는 Grafana 규칙이 맡는다.
 */
@Slf4j
@Component
@ConditionalOnExpression(
    "'${app.event-publisher.type}' == 'kafka' or '${app.event-publisher.type}' == 'outbox'")
@RequiredArgsConstructor
public class EventMessageSender {

  private final KafkaTemplate<String, DomainEventEnvelope> kafkaTemplate;
  private final MeterRegistry meterRegistry;

  /**
   * 봉투를 메시지로 만든다.
   *
   * <p>토픽은 봉투가 이미 갖고 있으나 파티션 키는 봉투에 없으므로 호출자가 넘긴다({@code DomainEvent.key()} 또는 outbox에 저장된
   * messageKey).
   */
  public Message<DomainEventEnvelope> toMessage(DomainEventEnvelope envelope, String key) {
    return MessageBuilder.withPayload(envelope)
        .setHeader(KafkaHeaders.TOPIC, envelope.topic())
        .setHeader(KafkaHeaders.KEY, key)
        .setHeader("eventType", envelope.eventType())
        .setHeader("eventId", envelope.eventId())
        .build();
  }

  /** 메시지를 비동기 발행하고, 실패하면 로그와 카운터로 남긴다. 예외는 호출자에게 전파하지 않는다. */
  public void send(Message<DomainEventEnvelope> message, DomainEventEnvelope envelope) {
    kafkaTemplate
        .send(message)
        .whenComplete(
            (result, ex) -> {
              if (ex != null) {
                recordFailure(envelope, ex);
              }
            });
  }

  private void recordFailure(DomainEventEnvelope envelope, Throwable ex) {
    log.error(
        "Failed to publish event to Kafka topic: {}, eventType={}, eventId={}",
        envelope.topic(),
        envelope.eventType(),
        envelope.eventId(),
        ex);

    // 토픽은 유한 집합이라 태그로 안전하다. eventId는 카디널리티가 무한이므로 태그로 쓰지 않는다.
    Counter.builder(MetricNames.EVENT_PUBLISH_FAILED)
        .tag(MetricNames.TAG_TOPIC, envelope.topic())
        .register(meterRegistry)
        .increment();
  }
}
