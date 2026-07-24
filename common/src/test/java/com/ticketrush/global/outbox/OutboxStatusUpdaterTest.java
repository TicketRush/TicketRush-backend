package com.ticketrush.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.notification.Notifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OutboxStatusUpdater} 단위 테스트.
 *
 * <p>상태 전이 로직은 {@link OutboxStatusTransition}에 위임하므로 여기서는 위임 후 알림 발송 여부만 검증한다. 전이 로직 자체의 단위 테스트는
 * {@link OutboxStatusTransitionTest}에서 다룬다.
 */
@ExtendWith(MockitoExtension.class)
class OutboxStatusUpdaterTest {

  private OutboxStatusUpdater outboxStatusUpdater;

  @Mock private OutboxStatusTransition transition;
  @Mock private Notifier notifier;

  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    outboxStatusUpdater = new OutboxStatusUpdater(transition, notifier, meterRegistry);
  }

  @Test
  @DisplayName("markSuccess: transition.markSuccess를 위임한다")
  void markSuccess_delegates_to_transition() {
    outboxStatusUpdater.markSuccess(1L);

    verify(transition).markSuccess(1L);
    verify(notifier, never()).send(any(), any(), any());
  }

  @Test
  @DisplayName("markFail: transition이 null을 반환하면(DEAD 아님) 알림을 발송하지 않는다")
  void markFail_does_not_notify_when_not_dead() {
    given(transition.markFail(1L, "boom")).willReturn(null);

    outboxStatusUpdater.markFail(1L, "boom");

    verify(notifier, never()).send(any(), any(), any());
  }

  @Test
  @DisplayName("markFail: transition이 DeadInfo를 반환하면(DEAD) 제목·식별자 포함 알림을 발송한다(#6 — lastError 미포함)")
  void markFail_notifies_when_dead() {
    given(transition.markFail(1L, "boom"))
        .willReturn(new OutboxStatusTransition.DeadInfo("evt-1", 1));

    outboxStatusUpdater.markFail(1L, "boom");

    // 알림 본문에 자유서식 오류 메시지(lastError) 대신 안전한 식별자 참조 문자열이 전달된다(#6).
    // 알림은 호출 스레드 밖에서 발송되므로(프로듀서 IO 스레드를 Slack 지연에 묶지 않기 위해) 대기 검증한다.
    verify(notifier, timeout(2000))
        .send(eq("[Outbox DEAD] 이벤트 발행 재시도 상한 초과"), any(String.class), any(Map.class));
  }

  @Test
  @DisplayName("markFail: 알림이 느려도 호출 스레드를 붙잡지 않는다")
  void markFail_does_not_block_caller_on_slow_notification() throws Exception {
    // given: Slack은 connect 3초 + read 5초라 DEAD 1건당 최대 8초가 걸린다. 이 메서드는 Kafka 프로듀서
    // 완료 콜백에서 불리고 그 IO 스레드는 모든 행의 콜백을 직렬 처리하므로, 여기서 기다리면 다른 행의
    // 상태 전이와 relay의 in-flight 해제가 함께 밀린다.
    CountDownLatch releaseNotifier = new CountDownLatch(1);
    CountDownLatch notifierEntered = new CountDownLatch(1);
    given(transition.markFail(1L, "boom"))
        .willReturn(new OutboxStatusTransition.DeadInfo("evt-1", 1));
    willAnswer(
            invocation -> {
              notifierEntered.countDown();
              releaseNotifier.await(5, TimeUnit.SECONDS); // 느린 알림
              return null;
            })
        .given(notifier)
        .send(any(), any(), any());

    try {
      // when
      outboxStatusUpdater.markFail(1L, "boom");

      // then: 알림이 아직 안 끝났는데도 호출이 돌아와 있다
      assertThat(notifierEntered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(releaseNotifier.getCount()).isEqualTo(1);
    } finally {
      releaseNotifier.countDown();
    }
  }
}
