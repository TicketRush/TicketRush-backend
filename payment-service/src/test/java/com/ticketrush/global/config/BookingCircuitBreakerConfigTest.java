package com.ticketrush.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
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
 * 서킷브레이커 오토컨피그가 이 스택(Spring Boot 4 + Spring Cloud 2025.1.0)에서 실제로 뜨는지, 그리고 #571 이 의존하는 두 가지 —
 * booking 설정 적용과 Prometheus 메트릭 노출 — 이 성립하는지 확인한다.
 *
 * <p>{@code PaymentServiceApplicationTests} 는 {@code classes = 자기 자신} 이라 이 설정이 컴포넌트 스캔으로 올라오긴 하지만
 * 임계값이 맞는지는 보지 않는다. 값 검증은 여기서만 이뤄진다.
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
  @DisplayName("오토컨피그가 뜨고 booking 서킷에 #633 실측으로 정한 임계값이 적용된다")
  void booking_circuit_breaker_config_is_applied() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(CircuitBreakerFactory.class);

          // 서킷은 create() 가 아니라 첫 run() 에서 레지스트리에 등록된다. 호출 없이 레지스트리를
          // 조회하면 기본 설정의 서킷이 새로 만들어져 이 검증이 통째로 무의미해진다.
          runOnce(context.getBean(CircuitBreakerFactory.class));

          CircuitBreakerConfig config =
              context
                  .getBean(CircuitBreakerRegistry.class)
                  .circuitBreaker(BookingCircuitBreakerConfig.BOOKING_CIRCUIT_BREAKER)
                  .getCircuitBreakerConfig();

          // 건수가 아니라 시간 기반이다 — 저트래픽 구간에서 어제의 실패가 오늘의 개폐를 결정하는 것을
          // 막기 위한 선택이고, ticket-service(COUNT_BASED)와 갈리는 지점이다.
          assertThat(config.getSlidingWindowType())
              .isEqualTo(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);
          assertThat(config.getSlidingWindowSize()).isEqualTo(60);
          assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
          assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
          assertThat(config.getSlowCallRateThreshold()).isEqualTo(50f);
          // 500ms(ticket)가 아니다 — #633 이 이 구간을 따로 실측해 50~100ms 가 세 run 모두
          // 건수 0 인 빈 구간임을 확인하고 정한 값이다. 복사해 오면 그 근거가 사라진다.
          assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofMillis(100));
          assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
              .isEqualTo(Duration.ofSeconds(10).toMillis());
          assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
          // 기본값 0(=타이머 없음)이면 HALF_OPEN 에서 permit 이 반환되지 않는 경우에 빠져나올 길이
          // 없다. fail-closed 경로라 그 상태는 결제 확정 영구 503 이다.
          assertThat(config.getMaxWaitDurationInHalfOpenState()).isEqualTo(Duration.ofSeconds(60));
        });
  }

  @Test
  @DisplayName("예매 없음(BOOKING_NOT_FOUND)은 서킷 창에서 제외된다 — 실패도 성공도 아니다")
  void booking_not_found_is_ignored_by_circuit_breaker() {
    CircuitBreakerConfig config = BookingCircuitBreakerConfig.bookingConfig();

    // ignoreException 축이어야 한다. recordException 으로 빼면 "실패로 안 센다"가 곧 "성공으로 센다"가
    // 되어, 404 홍수가 minimumNumberOfCalls 를 대신 채우고 실패율까지 희석한다.
    assertThat(
            config
                .getIgnoreExceptionPredicate()
                .test(new BusinessException(ErrorStatus.BOOKING_NOT_FOUND)))
        .isTrue();

    // 판정 불가(통신 실패·타임아웃·계약 붕괴)는 서킷이 반드시 세야 하는 갈래다.
    assertThat(
            config
                .getIgnoreExceptionPredicate()
                .test(new BusinessException(ErrorStatus.PAYMENT_BOOKING_COMMUNICATION_FAILED)))
        .isFalse();
    assertThat(config.getIgnoreExceptionPredicate().test(new IllegalStateException("boom")))
        .isFalse();
  }

  @Test
  @DisplayName("서킷 상태·호출수 메트릭이 MeterRegistry 에 등록된다 — 배포 직후 오탐 감시가 여기에 의존한다")
  void circuit_breaker_metrics_are_registered() {
    contextRunner.run(
        context -> {
          runOnce(context.getBean(CircuitBreakerFactory.class));

          MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
          assertThat(
                  meterRegistry
                      .find("resilience4j.circuitbreaker.state")
                      .tags("name", BookingCircuitBreakerConfig.BOOKING_CIRCUIT_BREAKER)
                      .gauges())
              .isNotEmpty();
          assertThat(meterRegistry.find("resilience4j.circuitbreaker.calls").meters()).isNotEmpty();

          // 차단 건수는 .calls 와 별개 meter 다. ADR 0019 와 build.gradle 주석이 "배포 직후 오탐
          // 감시는 이 축에 의존한다"고 못박았으므로, 이름이 바뀌거나 빠지면 여기서 드러나야 한다.
          assertThat(
                  meterRegistry
                      .find("resilience4j.circuitbreaker.not.permitted.calls")
                      .tags("name", BookingCircuitBreakerConfig.BOOKING_CIRCUIT_BREAKER)
                      .meters())
              .isNotEmpty();
        });
  }

  /**
   * ⚠ 이 테스트는 키를 <b>여기서 직접 적어</b> 넣으므로 {@code application.yml} 쪽 오타는 잡지 못한다. 고정하는 것은 "이 키 이름이 {@code
   * Resilience4JConfigurationProperties} 에 바인딩된다"는 것까지다 — 라이브러리가 키를 바꾸면 여기서 드러난다.
   */
  @Test
  @DisplayName("스레드풀·TimeLimiter 비활성 프로퍼티 키가 바인딩 대상과 일치한다")
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

  private static void runOnce(CircuitBreakerFactory<?, ?> factory) {
    factory.create(BookingCircuitBreakerConfig.BOOKING_CIRCUIT_BREAKER).run(() -> "ok");
  }
}
