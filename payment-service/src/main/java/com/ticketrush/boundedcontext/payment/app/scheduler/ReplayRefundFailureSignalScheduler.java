package com.ticketrush.boundedcontext.payment.app.scheduler;

import com.ticketrush.boundedcontext.payment.app.usecase.PaymentReplayRefundFailureSignalUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미해결 환불 실패의 보상 신호를 주기적으로 재발행한다 (#574).
 *
 * <p>정상 경로에서는 원 발행이 즉시 게이트를 열기 때문에 이 스케줄러가 할 일이 없다. 유실됐을 때만 의미가 있는 복구 경로라 초 단위 주기가 필요하지 않다. {@code
 * enabled=false} 로 끌 수 있다(재기동 필요).
 *
 * <p><b>ShedLock 을 붙이지 않는다.</b> booking·seat 의 outbox relay 와 달리 이 작업은 <b>읽기와 발행뿐이라 전이할 상태가 없다</b>.
 * 여러 인스턴스가 동시에 돌아도 같은 refund 는 같은 eventId 로 발행되므로 수신 측 Inbox 의 {@code (consumer_group, event_id)}
 * unique 가 흡수한다. 락을 위해 ShedLock 을 들이면 {@code RedisLockProvider} 가 필요하고, 그러려면 payment 가 명시적으로 끈
 * Redis 오토컨피그(#425)를 되살려 이 서비스를 Redis 장애 범위(ADR 0008)에 새로 넣어야 한다 — 얻는 것에 비해 대가가 크다. 근거는 ADR 0014.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.refund-signal-replay",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ReplayRefundFailureSignalScheduler {

  private final PaymentReplayRefundFailureSignalUseCase paymentReplayRefundFailureSignalUseCase;
  private final int batchSize;

  public ReplayRefundFailureSignalScheduler(
      PaymentReplayRefundFailureSignalUseCase paymentReplayRefundFailureSignalUseCase,
      @Value("${app.refund-signal-replay.batch-size:100}") int batchSize) {
    this.paymentReplayRefundFailureSignalUseCase = paymentReplayRefundFailureSignalUseCase;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${app.refund-signal-replay.interval-ms:300000}")
  public void replay() {
    paymentReplayRefundFailureSignalUseCase.execute(batchSize);
  }
}
