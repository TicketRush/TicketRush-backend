package com.ticketrush.boundedcontext.seat.out.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusChangedResponse;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusSseEmitterRegistry;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.types.SeatStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 거부(RejectedExecutionException) 경로 전용 테스트.
 *
 * <p>거부는 지연이 아니라 유실이고 로그 한 줄이 유일한 증적이라, 그 한 줄이 '거부된 순간의 큐 상태' 를 담고 있는지가 이 클래스의 관심사다. #403 실측에서 큐
 * 깊이가 0으로 보이는 구간의 거부를 설명하지 못했던 것이 이 로그를 바꾼 이유다.
 */
class SeatStatusSseEventSenderTest {

  private ThreadPoolTaskExecutor executor;
  private SeatStatusSseEventSender sender;
  private ListAppender<ILoggingEvent> logAppender;
  private Logger logger;
  private CountDownLatch blocker;
  private MeterRegistry meterRegistry;

  private static final SeatStatusChangedResponse EVENT =
      new SeatStatusChangedResponse(1L, 2L, 3L, "A-1", SeatStatus.HOLD, null);

  @BeforeEach
  void setUp() {
    // core 1 / max 1 / queue 1 로 좁혀 두 번째 제출부터 확정적으로 거부되게 만든다.
    executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.initialize();

    meterRegistry = new SimpleMeterRegistry();
    sender =
        new SeatStatusSseEventSender(new SeatStatusSseEmitterRegistry(), executor, meterRegistry);

    logger = (Logger) LoggerFactory.getLogger(SeatStatusSseEventSender.class);
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
  @DisplayName("거부된 이벤트는 예외를 밖으로 내보내지 않고 거부 시점의 큐 상태를 로그에 남긴다")
  void logsQueueDepthOnRejection() throws Exception {
    // given — 유일한 스레드를 붙잡고, 용량 1짜리 큐도 채워 다음 제출이 거부되게 만든다
    CountDownLatch running = new CountDownLatch(1);
    executor.execute(
        () -> {
          running.countDown();
          awaitBlocker();
        });
    assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
    executor.execute(this::awaitBlocker); // 큐(1) 를 채운다

    // when — 여기서 거부가 난다
    assertThatCode(() -> sender.send(EVENT)).doesNotThrowAnyException();

    // then
    assertThat(logAppender.list)
        .singleElement()
        .satisfies(
            log -> {
              assertThat(log.getLevel()).isEqualTo(Level.WARN);
              String message = log.getFormattedMessage();
              // 큐 깊이(=용량)와 풀 상태가 이벤트와 같은 줄에 있어야 원인을 가를 수 있다
              assertThat(message).contains("queue=1/1");
              assertThat(message).contains("activeThreads=1");
              assertThat(message).contains("poolSize=1/1");
              assertThat(message).contains("seatId=2");
              // 스택 트레이스는 남기지 않는다 — 발생 지점이 늘 같아 알려주는 것이 없다
              assertThat(log.getThrowableProxy()).isNull();
            });
    // 카운터가 '얼마나 사라졌나' 를 답한다. 로그 파싱 없이 유실률을 관측하는 유일한 축이다.
    assertThat(rejectedCount()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("거부되지 않으면 로그도 카운터도 움직이지 않는다")
  void doesNotLogWhenAccepted() {
    sender.send(EVENT);

    assertThat(logAppender.list).isEmpty();
    assertThat(rejectedCount()).isZero();
  }

  private double rejectedCount() {
    return meterRegistry.counter(MetricNames.SEAT_SSE_EVENT_REJECTED).count();
  }

  private void awaitBlocker() {
    try {
      blocker.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
