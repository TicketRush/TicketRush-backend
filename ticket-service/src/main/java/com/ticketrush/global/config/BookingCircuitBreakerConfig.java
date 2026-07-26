package com.ticketrush.global.config;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * booking-service 동기 호출({@link
 * com.ticketrush.boundedcontext.ticket.out.apiclient.BookingRestClient})의 서킷브레이커 설정(#496).
 *
 * <p>검표 경로는 요청마다 booking 을 한 번 왕복한다. 서킷이 없으면 booking 이 느려지는 순간 요청마다 톰캣 스레드가 read-timeout 만큼 묶여 검표
 * 경로 전체가 함께 마른다. 서킷은 그 전파를 끊어 "booking 이 죽으면 검표만 죽는다"로 범위를 좁힌다.
 */
@Configuration
public class BookingCircuitBreakerConfig {

  /** 서킷 인스턴스 이름. {@code BookingRestClient} 가 같은 이름으로 생성하고 메트릭 라벨에도 이 값이 붙는다. */
  public static final String BOOKING_CIRCUIT_BREAKER = "booking";

  @Bean
  public Customizer<Resilience4JCircuitBreakerFactory> bookingCircuitBreakerCustomizer() {
    return factory ->
        factory.configure(
            builder -> builder.circuitBreakerConfig(bookingConfig()), BOOKING_CIRCUIT_BREAKER);
  }

  /**
   * 임계값은 #402 실측을 기준선으로 잡았다. 정상 왕복이 3.20ms(서버 축 차분, 피크)이므로 느린호출 임계를 넘는 호출은 이미 비정상이다 —
   * read-timeout(1s)에 걸리지 않는 '느려짐'까지 실패로 세야 이 이슈가 겨냥한 시나리오(booking 이 죽지 않고 느려지는 경우)에서 서킷이 열린다.
   *
   * <p><b>느린호출 임계가 300ms 가 아니라 500ms 인 이유는 실측이다</b>(#496). 300ms 로 측정했을 때 <b>배포 직후 JIT 워밍업 구간에서 서킷이
   * 오탐으로 열렸다</b> — 실패 호출은 0건인데 차단된 호출이 1,472건이었다. booking 은 멀쩡했고 워밍업이 끝난 뒤에는 slow_call_rate 가 0
   * 이었다. 상한을 read-timeout(1s)까지 올리면 느린호출 축 자체가 무의미해지므로 500ms 를 절충선으로 둔다. 실측 왕복 기준으로는 여전히 156배 여유다.
   * 근거: {@code load-tests/k6/results/260727-496-booking-outage/report.md} §5.3.
   *
   * <p>public 인 이유는 테스트가 {@code CircuitBreakerConfig.from(...)} 으로 이 설정을 파생해 윈도우 크기·대기 시간만 줄여 쓰기
   * 위함이다. 실패 판정 규칙({@link #isDownstreamFailure})을 테스트가 손으로 복제하면 규칙이 바뀌어도 테스트가 눈치채지 못한다.
   */
  public static CircuitBreakerConfig bookingConfig() {
    return CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(20)
        .minimumNumberOfCalls(10)
        .failureRateThreshold(50)
        .slowCallDurationThreshold(Duration.ofMillis(500))
        .slowCallRateThreshold(50)
        .waitDurationInOpenState(Duration.ofSeconds(10))
        .permittedNumberOfCallsInHalfOpenState(3)
        .recordException(BookingCircuitBreakerConfig::isDownstreamFailure)
        .build();
  }

  /**
   * 예매 없음(404 → {@code TICKET_NOT_FOUND})은 booking-service 가 <b>정상 동작 중일 때</b> 나오는 응답이므로 서킷의 실패로 세지
   * 않는다. 세면 존재하지 않는 예매를 반복 스캔하는 것만으로 서킷이 열려 멀쩡한 검표가 전부 503 이 된다.
   */
  private static boolean isDownstreamFailure(Throwable throwable) {
    return !(throwable instanceof BusinessException businessException
        && businessException.getErrorStatus() == ErrorStatus.TICKET_NOT_FOUND);
  }
}
