package com.ticketrush.global.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketrush.global.notification.Notifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
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
    verify(notifier).send(eq("[Outbox DEAD] 이벤트 발행 재시도 상한 초과"), any(String.class), any(Map.class));
  }
}
