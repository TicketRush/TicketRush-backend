package com.ticketrush.boundedcontext.seat.out.sse;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusChangedResponse;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusEventSender;
import com.ticketrush.boundedcontext.seat.app.support.SeatStatusSseEmitterRegistry;
import com.ticketrush.global.constants.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class SeatStatusSseEventSender implements SeatStatusEventSender {

  private static final String EVENT_NAME = "seat-status-changed";

  private final SeatStatusSseEmitterRegistry seatStatusSseEmitterRegistry;
  private final ThreadPoolTaskExecutor seatStatusSseExecutor;
  // 카운터는 생성 시 한 번만 만든다. 거부는 버스트로 몰려 오고(#403 실측 최대 초당 728건)
  // 그 구간마다 Counter.builder(...).register(...) 를 호출하면 레지스트리 조회가 거부 경로에 얹힌다.
  private final Counter rejectedCounter;

  public SeatStatusSseEventSender(
      SeatStatusSseEmitterRegistry seatStatusSseEmitterRegistry,
      @Qualifier("seatStatusSseExecutor") ThreadPoolTaskExecutor seatStatusSseExecutor,
      MeterRegistry meterRegistry) {
    this.seatStatusSseEmitterRegistry = seatStatusSseEmitterRegistry;
    this.seatStatusSseExecutor = seatStatusSseExecutor;
    this.rejectedCounter =
        Counter.builder(MetricNames.SEAT_SSE_EVENT_REJECTED).register(meterRegistry);
  }

  @Override
  public void send(SeatStatusChangedResponse event) {
    try {
      seatStatusSseExecutor.execute(() -> broadcast(event));
    } catch (RejectedExecutionException e) {
      rejectedCounter.increment();
      logRejection(event);
    }
  }

  // 거부는 지연이 아니라 '유실' 이다 — 구독자는 이벤트가 안 왔다는 사실조차 모른다.
  // 카운터가 '얼마나 사라졌나' 를 답하고, 이 로그가 '왜 사라졌나' 를 답한다. 후자를 위해서는
  // 거부된 그 순간의 큐 상태가 이벤트와 같은 줄에 있어야 한다.
  // 스택 트레이스는 남기지 않는다. AbortPolicy 의 예외는 발생 지점이 늘 execute() 한 곳이라
  // 알려주는 것이 없는데, #403 실측에서는 초당 최대 728건이 찍혀 로그를 뒤덮었다.
  private void logRejection(SeatStatusChangedResponse event) {
    ThreadPoolExecutor pool = seatStatusSseExecutor.getThreadPoolExecutor();
    BlockingQueue<Runnable> queue = pool.getQueue();
    int queued = queue.size();
    log.warn(
        "좌석 상태 SSE 이벤트 전송 작업이 거부되었습니다(이 이벤트는 구독자에게 전달되지 않습니다). "
            + "queue={}/{} activeThreads={} poolSize={}/{} event: {}",
        queued,
        queued + queue.remainingCapacity(),
        pool.getActiveCount(),
        pool.getPoolSize(),
        pool.getMaximumPoolSize(),
        event);
  }

  private void broadcast(SeatStatusChangedResponse event) {
    List<SseEmitter> emitters = seatStatusSseEmitterRegistry.get(event.performanceId());
    if (emitters == null || emitters.isEmpty()) {
      return;
    }

    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name(EVENT_NAME).data(event));
      } catch (IOException | IllegalStateException e) {
        seatStatusSseEmitterRegistry.remove(event.performanceId(), emitter);
        emitter.completeWithError(e);
      }
    }
  }
}
