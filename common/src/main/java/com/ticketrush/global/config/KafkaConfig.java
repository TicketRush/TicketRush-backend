package com.ticketrush.global.config;

import com.ticketrush.global.event.DomainEventEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

@Slf4j
@Configuration
@EnableKafka
@RequiredArgsConstructor
@ConditionalOnExpression(
    "'${app.event-publisher.type}' == 'kafka' or '${app.event-publisher.type}' == 'outbox'")
public class KafkaConfig {

  private final MeterRegistry meterRegistry;

  private static final String DLT_SUFFIX = ".DLT";
  private static final String TRUSTED_EVENT_PACKAGE = "com.ticketrush.*";
  private static final String ACKS_ALL = "all";
  private static final String OFFSET_RESET_LATEST = "latest";
  private static final String UNKNOWN = "UNKNOWN";

  private static final int PRODUCER_RETRIES = 3;
  private static final int MAX_POLL_RECORDS = 20;
  private static final int MAX_IN_FLIGHT_REQUESTS = 5;
  private static final int LINGER_MS = 5;
  private static final int BACKOFF_MAX_RETRIES = 5;

  private static final int FETCH_MAX_WAIT_MS = 500;
  private static final int MAX_POLL_INTERVAL_MS = 300_000;
  private static final int DELIVERY_TIMEOUT_MS = 120_000;
  // send()가 브로커 메타데이터·버퍼 대기로 동기 블로킹되는 최대 시간. 기본 60초는 outbox relay가
  // @Scheduled 스레드에서 send()를 호출하므로 브로커가 불가할 때 그 스레드를 60초씩 문다. 5초로 잘라
  // relay가 스레드를 오래 붙잡지 않게 한다. relay 주기(5s)와 정합하며, 짧은 메타데이터 갱신·리더 선출은
  // 견딘다. 장기 브로커 장애 시에는 어차피 발행이 불가하고, DEAD 전이(max-retries)와 DLT/알림이 받는다.
  private static final int MAX_BLOCK_MS = 5_000;
  private static final long BACKOFF_INITIAL_INTERVAL_MS = 1_000L;
  private static final long BACKOFF_MAX_INTERVAL_MS = 60_000L;

  private static final double BACKOFF_MULTIPLIER = 2.0;

  private static final boolean ENABLE_IDEMPOTENCE = true;
  private static final boolean ENABLE_AUTO_COMMIT = false;
  private static final boolean USE_TYPE_INFO_HEADERS = true;

  @Value("${spring.kafka.bootstrap-servers:localhost:29092}")
  private String bootstrapServers;

  /*
   * 리스너 컨테이너의 컨슈머 스레드 수 (#596). 브로커가 KAFKA_NUM_PARTITIONS: 3 으로 뜨고 NewTopic 빈이 있는 토픽은
   * performance-events 하나뿐이라 나머지 9개는 auto-create 로 이미 3파티션인데, 이 팩토리에 setConcurrency 가 없어
   * 실효 병렬도가 1이었다. 파티션 3개를 스레드 1개가 먹고 있었다는 뜻이다.
   *
   * 3이 상한인 이유: 파티션 수를 넘는 스레드는 할당받을 파티션이 없어 그냥 논다. 값 검증 코드를 두지 않는 이유도 같다 —
   * 상한은 Kafka 가 흡수하고, 1 미만은 ConcurrentMessageListenerContainer.setConcurrency 가 기동 시
   * IllegalArgumentException 으로 잘라낸다.
   *
   * Boot 자동설정(ConcurrentKafkaListenerContainerFactoryConfigurer)은 kafkaListenerContainerFactory
   * 라는 같은 이름의 빈이 있으면 back off 한다. 즉 이 프로퍼티를 yml 에 적어두기만 해서는 아무 효과가 없고,
   * 여기서 직접 읽어야 실제로 적용된다.
   *
   * 환경변수 SPRING_KAFKA_LISTENER_CONCURRENCY 로 재배포 없이 바꿀 수 있다(relaxed binding). #598 이 같은
   * 배포본에서 이 값만 1↔3 으로 토글해 A/B 두 arm 을 돌린다 — 배포로 arm 을 가르면 이미지가 달라져 대조가 흔들리기
   * 때문이고, #554 가 QUEUE_ENABLED 로 같은 선례를 세웠다.
   *
   * ⚠ 되돌릴 조건 — 다음 중 하나가 관측되면 1로 되돌린다.
   *   1) hikaricp_connections_pending > 0. 커넥션 풀은 서비스당 기본 10인데 booking 은 리스너 5개 × 3 = 15 스레드,
   *      seat 은 3개 × 3 = 9 스레드다. 커넥션을 못 받아 타임아웃나면 Kafka 재시도 → 5회 후 DLT 로 번진다.
   *      "지금까지 pending=0 이라 풀을 튜닝하지 않았다"는 근거가 깨지는 지점이 정확히 여기다.
   *   2) 호스트 CPU 가 이미 상한인데 처리량이 늘지 않는 경우. 2 vCPU 다(ADR 0006). 스레드만 늘리면 컨텍스트
   *      스위칭만 는다(#509 에서 확인된 실패 모드).
   *   3) 컨테이너 RSS 가 mem_limit 에 근접하는 경우. seat-service 는 640 MiB 상한에서 이미 cgroup OOM 으로
   *      죽은 적이 있다(#509).
   */
  @Value("${spring.kafka.listener.concurrency:3}")
  private int listenerConcurrency;

  @Bean
  public ProducerFactory<String, DomainEventEnvelope> producerFactory() {
    Map<String, Object> configProps = new HashMap<>();
    configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
    configProps.put(ProducerConfig.ACKS_CONFIG, ACKS_ALL);
    configProps.put(ProducerConfig.RETRIES_CONFIG, PRODUCER_RETRIES);
    configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, DELIVERY_TIMEOUT_MS);
    configProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS);
    configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, ENABLE_IDEMPOTENCE);
    configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, MAX_IN_FLIGHT_REQUESTS);
    configProps.put(ProducerConfig.LINGER_MS_CONFIG, LINGER_MS);
    configProps.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, USE_TYPE_INFO_HEADERS);

    DefaultKafkaProducerFactory<String, DomainEventEnvelope> factory =
        new DefaultKafkaProducerFactory<>(configProps);
    factory.addListener(new MicrometerProducerListener<>(meterRegistry));
    return factory;
  }

  @Bean
  public KafkaTemplate<String, DomainEventEnvelope> kafkaTemplate() {
    return new KafkaTemplate<>(producerFactory());
  }

  @Bean
  public ConsumerFactory<String, DomainEventEnvelope> consumerFactory() {
    Map<String, Object> configProps = new HashMap<>();
    configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

    configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
    configProps.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);

    configProps.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
    configProps.put(
        ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class);

    configProps.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, TRUSTED_EVENT_PACKAGE);
    configProps.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, USE_TYPE_INFO_HEADERS);

    configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, OFFSET_RESET_LATEST);
    configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, ENABLE_AUTO_COMMIT);
    configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLL_RECORDS);
    configProps.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, FETCH_MAX_WAIT_MS);
    configProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, MAX_POLL_INTERVAL_MS);

    DefaultKafkaConsumerFactory<String, DomainEventEnvelope> factory =
        new DefaultKafkaConsumerFactory<>(configProps);
    factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
    return factory;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, DomainEventEnvelope>
      kafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, DomainEventEnvelope> factory =
        new ConcurrentKafkaListenerContainerFactory<>();

    factory.setConsumerFactory(consumerFactory());
    factory.setConcurrency(listenerConcurrency);
    factory.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE);

    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate(),
            (record, ex) -> {
              String eventId = UNKNOWN;
              String eventType = UNKNOWN;

              if (record.value() instanceof DomainEventEnvelope envelope) {
                eventId = envelope.eventId();
                eventType = envelope.eventType();
              }

              log.error(
                  "[DLT] topic={} partition={} offset={} key={} eventType={} eventId={}",
                  record.topic(),
                  record.partition(),
                  record.offset(),
                  record.key(),
                  eventType,
                  eventId,
                  ex);

              return new TopicPartition(toDltTopic(record.topic()), record.partition());
            });

    ExponentialBackOffWithMaxRetries backOff =
        new ExponentialBackOffWithMaxRetries(BACKOFF_MAX_RETRIES);
    backOff.setInitialInterval(BACKOFF_INITIAL_INTERVAL_MS);
    backOff.setMultiplier(BACKOFF_MULTIPLIER);
    backOff.setMaxInterval(BACKOFF_MAX_INTERVAL_MS);

    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
    errorHandler.addNotRetryableExceptions(
        DeserializationException.class, ClassCastException.class, IllegalArgumentException.class);
    errorHandler.addRetryableExceptions(RetriableException.class);
    // MANUAL 계열 ack 모드에서는 복구(DLT 발행)된 레코드의 오프셋이 기본적으로 커밋되지 않아,
    // 리밸런스·재시작 시 poison 메시지가 재처리되어 DLT에 중복 적재된다. 복구 시점에 커밋한다.
    errorHandler.setCommitRecovered(true);

    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }

  private String toDltTopic(String topic) {
    return topic + DLT_SUFFIX;
  }
}
