package com.ticketrush.global.config;

import com.ticketrush.shared.performance.event.PerformanceCreatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;

@Configuration
@ConditionalOnExpression(
    "'${app.event-publisher.type}' == 'kafka' or '${app.event-publisher.type}' == 'outbox'")
public class KafkaTopicConfig {

  @Value("${app.kafka.topics.performance-events.partitions:1}")
  private int partitions;

  @Value("${app.kafka.topics.performance-events.replicas:1}")
  private short replicas;

  @Bean
  public NewTopics performanceKafkaTopics() {
    return new NewTopics(
        TopicBuilder.name(PerformanceCreatedEvent.TOPIC)
            .partitions(partitions)
            .replicas(replicas)
            .build());
  }
}
