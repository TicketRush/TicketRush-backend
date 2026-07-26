package com.ticketrush.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerMetricsAutoConfiguration;
import io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JAutoConfiguration;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

/**
 * 서킷브레이커 오토컨피그가 이 스택(Spring Boot 4 + Spring Cloud 2025.1.0)에서 실제로 뜨는지, 그리고 #496 이 의존하는 두 가지 —
 * booking 설정 적용과 Prometheus 메트릭 노출 — 이 성립하는지 확인한다.
 *
 * <p>{@code TicketServiceApplicationTests} 는 {@code classes = 자기 자신} 이라 오토컨피그를 돌리지 않는다. 그래서 이 검증은
 * 여기서만 이뤄진다.
 */
class BookingCircuitBreakerConfigTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  Resilience4JAutoConfiguration.class,
                  CircuitBreakerAutoConfiguration.class,
                  CircuitBreakerMetricsAutoConfiguration.class,
                  // Resilience4JAutoConfiguration 이 TimeLimiterRegistry 를 요구한다.
                  // 실행 중인 앱에서는 스타터가 전부 올려주지만 여기서는 명시해야 한다.
                  TimeLimiterAutoConfiguration.class))
          .withBean(SimpleMeterRegistry.class)
          .withUserConfiguration(BookingCircuitBreakerConfig.class);

  @Test
  @DisplayName("오토컨피그가 뜨고 booking 서킷에 이 프로젝트의 임계값이 적용된다")
  void booking_circuit_breaker_config_is_applied() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(CircuitBreakerFactory.class);

          // 서킷은 create() 가 아니라 첫 run() 에서 레지스트리에 등록된다. 호출 없이 레지스트리를
          // 조회하면 기본 설정의 서킷이 새로 만들어져 이 검증이 통째로 무의미해진다.
          runOnce(context.getBean(CircuitBreakerFactory.class));

          var config =
              context
                  .getBean(CircuitBreakerRegistry.class)
                  .circuitBreaker(BookingCircuitBreakerConfig.BOOKING_CIRCUIT_BREAKER)
                  .getCircuitBreakerConfig();

          assertThat(config.getSlidingWindowSize()).isEqualTo(20);
          assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
          assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
          // 300ms 가 아니다 — #496 실측에서 배포 직후 워밍업 구간의 정상 호출이 300ms 를 넘겨
          // 서킷이 오탐으로 열렸다(실패 0건, 차단 1472건). 되돌리면 그 회귀가 다시 난다.
          assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofMillis(500));
          assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        });
  }

  @Test
  @DisplayName("서킷 상태·호출수 메트릭이 MeterRegistry 에 등록된다 — 장애 주입 측정의 전/후 비교가 여기에 의존한다")
  void circuit_breaker_metrics_are_registered() {
    contextRunner.run(
        context -> {
          runOnce(context.getBean(CircuitBreakerFactory.class));

          MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
          assertThat(
                  meterRegistry
                      .find("resilience4j.circuitbreaker.state")
                      .tags("name", "booking")
                      .gauges())
              .isNotEmpty();
          assertThat(meterRegistry.find("resilience4j.circuitbreaker.calls").meters()).isNotEmpty();
        });
  }

  private static void runOnce(CircuitBreakerFactory<?, ?> factory) {
    factory.create(BookingCircuitBreakerConfig.BOOKING_CIRCUIT_BREAKER).run(() -> "ok");
  }

  @Test
  @DisplayName("application.yml 의 스레드풀·TimeLimiter 비활성 프로퍼티 키가 실제로 바인딩된다")
  void threadpool_and_timelimiter_are_disabled_by_properties() {
    contextRunner
        .withPropertyValues(
            "spring.cloud.circuitbreaker.resilience4j.disable-threadpool=true",
            "spring.cloud.circuitbreaker.resilience4j.disable-timelimiter=true")
        .run(
            context -> {
              Resilience4JConfigurationProperties properties =
                  context.getBean(Resilience4JConfigurationProperties.class);
              assertThat(properties.isDisableThreadPool()).isTrue();
              assertThat(properties.isDisableTimeLimiter()).isTrue();
            });
  }
}
