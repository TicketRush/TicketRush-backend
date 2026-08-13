package com.ticketrush.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.event.DomainEventEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

/**
 * 컨슈머 병렬도가 설정으로 주입되는지 검증한다(#596).
 *
 * <p>브로커가 필요 없다. {@code createContainer}는 컨테이너 객체만 만들고 {@code start()} 전까지 연결하지 않으므로, 팩토리에 주입된 값이
 * 실제 컨테이너에 실리는지까지 오프라인으로 확인할 수 있다.
 *
 * <p>세 번째 테스트가 이 파일의 존재 이유다. #598은 <b>같은 배포본에서 환경변수만 1↔3으로 바꿔</b> A/B 두 arm을 돌리므로, 프로퍼티가 아니라
 * <b>환경변수 이름</b>으로 값이 들어오는 경로가 도는지가 그 회차의 전제다. Boot가 OS 환경변수에 실제로 쓰는 클래스인 {@link
 * SystemEnvironmentPropertySource}를 그대로 넣어 relaxed binding을 증명한다 — 이름만 맞춘 일반 프로퍼티로는 이 경로가 검증되지 않는다.
 *
 * <p><b>테스트 대상은 common의 {@code KafkaConfig}인데 이 테스트가 seat-service에 있는 이유</b>는 common 모듈의 클래스패스에서
 * {@code KafkaConfig}를 띄울 수 없기 때문이다. common은 {@code tools.jackson.core:jackson-databind:3.1.0}을 직접
 * 선언하는데 그게 요구하는 {@code jackson-annotations:2.21}이 Boot BOM에 의해 2.20으로 내려가, {@code
 * JacksonJsonSerializer}의 static 초기화가 {@code NoClassDefFoundError: JsonSerializeAs}로 깨진다. 서비스 모듈에서는
 * BOM이 databind를 3.0.4로 정렬해 이 조합이 생기지 않는다(운영이 정상인 이유도 이것이다). 즉 여기서 도는 것이 <b>서비스가 실제로 조립하는 그
 * 설정</b>이라 증거로도 이쪽이 낫다. common 쪽 pin을 손대는 것은 전 모듈 영향이라 이 이슈에서 다루지 않는다.
 */
class KafkaConfigConcurrencyTest {

  private static final String CONCURRENCY_PROPERTY = "spring.kafka.listener.concurrency";
  private static final String CONCURRENCY_ENV_VAR = "SPRING_KAFKA_LISTENER_CONCURRENCY";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(KafkaConfig.class)
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          // KafkaConfig의 @ConditionalOnExpression 통과용
          .withPropertyValues("app.event-publisher.type=kafka");

  @Test
  @DisplayName("기본값은 3이다 — 토픽 파티션 수와 같다")
  void defaults_to_partition_count() {
    contextRunner.run(context -> assertThat(concurrencyOf(context)).isEqualTo(3));
  }

  @Test
  @DisplayName("프로퍼티로 병렬도를 낮출 수 있다")
  void property_overrides_default() {
    contextRunner
        .withPropertyValues(CONCURRENCY_PROPERTY + "=1")
        .run(context -> assertThat(concurrencyOf(context)).isEqualTo(1));
  }

  @Test
  @DisplayName("환경변수 SPRING_KAFKA_LISTENER_CONCURRENCY로도 주입된다 (#598 A/B 전제)")
  void environment_variable_overrides_default() {
    contextRunner
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new SystemEnvironmentPropertySource(
                            "test-system-environment", Map.of(CONCURRENCY_ENV_VAR, "2"))))
        .run(context -> assertThat(concurrencyOf(context)).isEqualTo(2));
  }

  @SuppressWarnings("unchecked")
  private int concurrencyOf(ApplicationContext context) {
    ConcurrentKafkaListenerContainerFactory<String, DomainEventEnvelope> factory =
        context.getBean(ConcurrentKafkaListenerContainerFactory.class);
    ConcurrentMessageListenerContainer<String, DomainEventEnvelope> container =
        factory.createContainer("any-topic");
    return container.getConcurrency();
  }
}
