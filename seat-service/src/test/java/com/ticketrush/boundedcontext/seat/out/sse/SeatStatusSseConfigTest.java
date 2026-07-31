package com.ticketrush.boundedcontext.seat.out.sse;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticketrush.global.constants.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 역압(CallerRunsPolicy) 경로 전용 테스트(#532).
 *
 * <p>#528 실측에서 큐 포화 시 기본 정책(AbortPolicy)이 이벤트 649건을 조용히 버렸다. 이 클래스의 관심사는 포화 시 태스크가 버려지는 대신 호출 스레드에서
 * 실행되는지, 그리고 그 대가(호출 스레드 지연)가 카운터로 관측되는지다.
 */
class SeatStatusSseConfigTest {

  private ThreadPoolTaskExecutor executor;
  private MeterRegistry meterRegistry;
  private ListAppender<ILoggingEvent> logAppender;
  private Logger logger;
  private CountDownLatch blocker;

  @BeforeEach
  void setUp() {
    // core 1 / max 1 / queue 1 로 좁혀 두 번째 제출부터 확정적으로 포화되게 만든다.
    meterRegistry = new SimpleMeterRegistry();
    executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setRejectedExecutionHandler(
        new SeatStatusSseConfig.BackpressureCallerRunsPolicy(meterRegistry));
    executor.initialize();

    logger = (Logger) LoggerFactory.getLogger(SeatStatusSseConfig.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);

    blocker = new CountDownLatch(1);
  }

  @AfterEach
  void tearDown() {
    blocker.countDown();
    logger.detachAppender(logAppender);
    executor.shutdown();
  }

  @Test
  @DisplayName("큐가 포화되면 태스크를 버리는 대신 호출 스레드가 실행하고, 그 횟수를 카운터와 로그에 남긴다")
  void runsOnCallerThreadWhenSaturated() throws Exception {
    // given — 유일한 스레드를 붙잡고, 용량 1짜리 큐도 채워 다음 제출이 포화를 만나게 한다
    CountDownLatch running = new CountDownLatch(1);
    executor.execute(
        () -> {
          running.countDown();
          awaitBlocker();
        });
    assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
    executor.execute(this::awaitBlocker); // 큐(1) 를 채운다

    // when — 포화 상태의 제출. AbortPolicy 였다면 여기서 유실됐을 태스크다
    AtomicReference<Thread> executedOn = new AtomicReference<>();
    executor.execute(() -> executedOn.set(Thread.currentThread()));

    // then — 유실 대신 호출 스레드(이 테스트 스레드)가 직접 실행했다 = 역압
    assertThat(executedOn.get()).isSameAs(Thread.currentThread());
    // 카운터가 '역압이 몇 번 발동했나' = 호출 스레드가 지불한 비용을 답한다
    assertThat(callerRunsCount()).isEqualTo(1.0);
    assertThat(logAppender.list)
        .singleElement()
        .satisfies(
            log -> {
              assertThat(log.getLevel()).isEqualTo(Level.WARN);
              String message = log.getFormattedMessage();
              // 포화 시점의 큐·풀 상태가 같은 줄에 있어야 원인을 가를 수 있다(#403과 같은 이유)
              assertThat(message).contains("queue=1/1");
              assertThat(message).contains("poolSize=1/1");
              // 스택 트레이스는 남기지 않는다 — 발생 지점이 늘 같아 알려주는 것이 없다
              assertThat(log.getThrowableProxy()).isNull();
            });
  }

  @Test
  @DisplayName("포화되지 않으면 풀 스레드가 실행하고 로그도 카운터도 움직이지 않는다")
  void runsOnPoolThreadWhenNotSaturated() throws Exception {
    AtomicReference<Thread> executedOn = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    executor.execute(
        () -> {
          executedOn.set(Thread.currentThread());
          done.countDown();
        });

    assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(executedOn.get()).isNotSameAs(Thread.currentThread());
    assertThat(callerRunsCount()).isZero();
    assertThat(logAppender.list).isEmpty();
  }

  private double callerRunsCount() {
    return meterRegistry.counter(MetricNames.SEAT_SSE_EVENT_CALLER_RUNS).count();
  }

  private void awaitBlocker() {
    try {
      blocker.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
