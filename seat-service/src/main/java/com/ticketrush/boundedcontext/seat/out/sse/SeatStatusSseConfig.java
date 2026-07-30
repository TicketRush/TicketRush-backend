package com.ticketrush.boundedcontext.seat.out.sse;

import com.ticketrush.global.constants.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 좌석 상태 SSE 팬아웃 스레드풀 설정.
 *
 * <p>거부 정책은 {@link BackpressureCallerRunsPolicy}(CallerRunsPolicy 계열)다(#532). 스케줄러 fallback이 tick당
 * 최대 2,000건(chunk-size × max-chunks)을 밀어 넣는데 큐 용량이 1,000이라, 만료 HOLD가 1,000건 넘게 쌓인 상태에서 tick이 돌면 기본
 * 정책(AbortPolicy)으로는 반드시 유실이 났다 — #528 실측에서 구독자 600명 조건에 649건이 조용히 사라졌다. 구독자는 이벤트가 안 왔다는 사실조차 모르므로,
 * 유실 대신 호출 스레드가 팬아웃을 떠안는 역압을 택했다.
 *
 * <p>기각한 대안(#532 본문): (A) 스케줄러 청크 유량 제한 — 만료 해소가 느려지고, 큐 포화는 도착률×구독자 수의 곱이라 스케줄러만 조여서는 못 막는다. (B) 큐
 * 용량을 tick 상한(2,000) 위로 — tick 상한 설정이 바뀌면 다시 깨지는 숫자 맞추기이고 유실 가능성 자체는 남는다.
 *
 * <p>역압의 대가: 포화 구간에서 호출 스레드(스케줄러 tick 또는 Redis 만료 리스너)가 팬아웃만큼 밀린다. 그 비용은 {@code
 * ticketrush_seat_sse_event_caller_runs_total}로 관측한다. 정상 상태(#528 전 구간 큐 깊이 0)에서는 발동하지 않는다.
 */
@Slf4j
@Configuration
public class SeatStatusSseConfig {

  // 반환 타입을 Executor 가 아니라 ThreadPoolTaskExecutor 로 두는 이유:
  // 거부(RejectedExecutionException)가 났을 때 '그 순간의 큐 깊이' 를 로그에 남기려면 sender 가
  // 풀 내부 상태를 읽어야 하는데 Executor 인터페이스로는 읽을 수 없다.
  // #403 실측에서 거부 2,009건 중 280건이 '큐 깊이가 0으로 보이는' 구간에 났고, 관측 주기가
  // 3초(폴링)/15초(스크랩)라 순간 포화를 못 본 것인지 다른 기전인지 갈라내지 못했다.
  // 거부 시점의 큐 깊이를 이벤트와 같은 줄에 남기면 그 질문이 로그 한 줄로 끝난다.
  @Bean
  public ThreadPoolTaskExecutor seatStatusSseExecutor(MeterRegistry meterRegistry) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("seat-status-sse-");
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(1000);
    executor.setRejectedExecutionHandler(new BackpressureCallerRunsPolicy(meterRegistry));
    executor.initialize();
    return executor;
  }

  // package-private: 풀(4/16/1000)을 포화시키지 않고도 핸들러 단독으로 테스트하기 위함
  static class BackpressureCallerRunsPolicy extends ThreadPoolExecutor.CallerRunsPolicy {

    // 생성 시 한 번만 등록한다 — 포화는 버스트로 오므로 발동 경로에 레지스트리 조회를 얹지 않는다
    private final Counter callerRunsCounter;

    BackpressureCallerRunsPolicy(MeterRegistry meterRegistry) {
      this.callerRunsCounter =
          Counter.builder(MetricNames.SEAT_SSE_EVENT_CALLER_RUNS).register(meterRegistry);
    }

    // 유실이 아니라 지연이므로 심각도는 warn 에 둔다. 스택 트레이스는 남기지 않는다 — 발생 지점이
    // 늘 execute() 한 곳이라 알려주는 것이 없고, #403 실측에서는 초당 최대 728건이 로그를 뒤덮었다.
    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
      callerRunsCounter.increment();
      BlockingQueue<Runnable> queue = pool.getQueue();
      int queued = queue.size();
      log.warn(
          "좌석 상태 SSE 전송 큐가 가득 차 호출 스레드가 팬아웃을 대신 실행합니다(이벤트는 유실되지 않습니다). "
              + "queue={}/{} activeThreads={} poolSize={}/{}",
          queued,
          queued + queue.remainingCapacity(),
          pool.getActiveCount(),
          pool.getPoolSize(),
          pool.getMaximumPoolSize());
      super.rejectedExecution(task, pool);
    }
  }
}
