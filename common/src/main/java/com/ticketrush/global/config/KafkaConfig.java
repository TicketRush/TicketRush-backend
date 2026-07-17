package com.ticketrush.global.config;

import com.ticketrush.global.event.DomainEventEnvelope;
import java.util.HashMap;
import java.util.Map;
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
@ConditionalOnExpression(
    "'${app.event-publisher.type}' == 'kafka' or '${app.event-publisher.type}' == 'outbox'")
public class KafkaConfig {

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
  private static final long BACKOFF_INITIAL_INTERVAL_MS = 1_000L;
  private static final long BACKOFF_MAX_INTERVAL_MS = 60_000L;

  private static final double BACKOFF_MULTIPLIER = 2.0;

  private static final boolean ENABLE_IDEMPOTENCE = true;
  private static final boolean ENABLE_AUTO_COMMIT = false;
  private static final boolean USE_TYPE_INFO_HEADERS = true;

  @Value("${spring.kafka.bootstrap-servers:localhost:29092}")
  private String bootstrapServers;

  @Bean
  public ProducerFactory<String, DomainEventEnvelope> producerFactory() {
    Map<String, Object> configProps = new HashMap<>();
    configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
    configProps.put(ProducerConfig.ACKS_CONFIG, ACKS_ALL);
    configProps.put(ProducerConfig.RETRIES_CONFIG, PRODUCER_RETRIES);
    configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, DELIVERY_TIMEOUT_MS);
    configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, ENABLE_IDEMPOTENCE);
    configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, MAX_IN_FLIGHT_REQUESTS);
    configProps.put(ProducerConfig.LINGER_MS_CONFIG, LINGER_MS);
    configProps.put(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, USE_TYPE_INFO_HEADERS);

    return new DefaultKafkaProducerFactory<>(configProps);
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

    return new DefaultKafkaConsumerFactory<>(configProps);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, DomainEventEnvelope>
      kafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, DomainEventEnvelope> factory =
        new ConcurrentKafkaListenerContainerFactory<>();

    factory.setConsumerFactory(consumerFactory());
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

    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }

  private String toDltTopic(String topic) {
    return topic + DLT_SUFFIX;
  }
}
