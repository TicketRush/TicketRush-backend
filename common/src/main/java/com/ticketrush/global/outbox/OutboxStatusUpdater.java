package com.ticketrush.global.outbox;

import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.notification.Notifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Kafka 비동기 발행 콜백에서 Outbox row의 상태를 전이한다.
 *
 * <p>콜백은 프로듀서 IO 스레드(relay 트랜잭션 밖)에서 실행된다. 상태 전이는 {@link OutboxStatusTransition}(별도 스프링 빈)의 {@code
 * REQUIRES_NEW} 메서드로 위임하고, 트랜잭션 커밋 이후에 {@link Notifier#send}를 호출한다. 이로써 외부 HTTP 지연(최대 8초)이 DB
 * 커넥션·row 락을 점유하지 않는다. 알림 호출 자체도 호출 스레드에서 떼어낸다 — 그 지연이 프로듀서 IO 스레드를 잡으면 다른 행의 상태 전이가 함께 밀리기
 * 때문이다({@link #markFail} 주석 참고).
 *
 * <p><b>self-invocation 주의:</b> 알림 호출을 같은 클래스의 private 메서드로 분리하면 Spring 프록시를 우회해 트랜잭션이 적용되지 않는다. 별도
 * 빈({@link OutboxStatusTransition})으로 분리해 이 문제를 회피한다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.event-publisher.type}' == 'outbox'")
@RequiredArgsConstructor
public class OutboxStatusUpdater {

  private final OutboxStatusTransition transition;
  private final Notifier notifier;
  private final MeterRegistry meterRegistry;

  public void markSuccess(Long id) {
    transition.markSuccess(id);
    Counter.builder(MetricNames.OUTBOX_RELAY)
        .tag(MetricNames.TAG_RESULT, MetricNames.RESULT_SUCCESS)
        .register(meterRegistry)
        .increment();
  }

  public void markFail(Long id, String lastError) {
    OutboxStatusTransition.DeadInfo deadInfo = transition.markFail(id, lastError);
    Counter.builder(MetricNames.OUTBOX_RELAY)
        .tag(MetricNames.TAG_RESULT, MetricNames.RESULT_FAIL)
        .register(meterRegistry)
        .increment();
    if (deadInfo != null) {
      // 트랜잭션 커밋 후 알림. 외부 HTTP 지연이 DB 커넥션을 점유하지 않는다(#1 결정).
      // 알림 본문에 자유서식 오류 메시지를 포함하지 않는다(#6 결정 — PII 유출 방지).
      // 호출 스레드에서 떼어내는 이유는 #344 측정 이후의 리뷰 지적이다. 이 메서드는 Kafka 프로듀서
      // 완료 콜백에서 불리는데 그 IO 스레드는 모든 행의 콜백을 직렬 처리한다. Slack 호출은
      // connect 3초 + read 5초라 DEAD 1건당 최대 8초 동안 그 스레드를 잡고, 그동안 다른 행의
      // 상태 전이와 relay의 in-flight 해제가 함께 밀린다. 알림은 실패해도 발행 정합성과 무관하므로
      // 결과를 기다리지 않고, 예외는 삼키지 않고 로그로 남긴다.
      CompletableFuture.runAsync(
              () ->
                  notifier.send(
                      "[Outbox DEAD] 이벤트 발행 재시도 상한 초과",
                      "상세는 outbox 테이블 eventId=" + deadInfo.eventId() + " 조회",
                      Map.of(
                          "eventId", deadInfo.eventId(),
                          "maxRetries", String.valueOf(deadInfo.maxRetries()))))
          .whenComplete(
              (ignored, ex) -> {
                if (ex != null) {
                  log.error("Outbox DEAD 알림 발송 실패. eventId={}", deadInfo.eventId(), ex);
                }
              });
    }
  }
}
