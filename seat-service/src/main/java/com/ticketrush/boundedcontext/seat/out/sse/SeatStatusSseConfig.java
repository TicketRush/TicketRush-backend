package com.ticketrush.boundedcontext.seat.out.sse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class SeatStatusSseConfig {

  // 반환 타입을 Executor 가 아니라 ThreadPoolTaskExecutor 로 두는 이유:
  // 거부(RejectedExecutionException)가 났을 때 '그 순간의 큐 깊이' 를 로그에 남기려면 sender 가
  // 풀 내부 상태를 읽어야 하는데 Executor 인터페이스로는 읽을 수 없다.
  // #403 실측에서 거부 2,009건 중 280건이 '큐 깊이가 0으로 보이는' 구간에 났고, 관측 주기가
  // 3초(폴링)/15초(스크랩)라 순간 포화를 못 본 것인지 다른 기전인지 갈라내지 못했다.
  // 거부 시점의 큐 깊이를 이벤트와 같은 줄에 남기면 그 질문이 로그 한 줄로 끝난다.
  @Bean
  public ThreadPoolTaskExecutor seatStatusSseExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("seat-status-sse-");
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(1000);
    executor.initialize();
    return executor;
  }
}
