package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
import com.ticketrush.boundedcontext.payment.out.repository.RefundRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.eventpublisher.EventReplayPublisher;
import com.ticketrush.shared.payment.event.RefundFailedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 미해결 환불 실패의 보상 신호를 booking 에 다시 알린다 (#574).
 *
 * <p><b>왜 필요한가.</b> PG 가 환불을 거절하면 payment 는 {@code refund} 에 FAILED 이력을 남기고 {@link
 * RefundFailedEvent} 를 발행한다. booking 은 그 이벤트를 받아야 {@code refundFailedAt} 을 찍고, 그래야 미해결 목록과 관리자 재환불
 * API 가 그 건을 잡는다. 그런데 발행은 fire-and-forget 이라 유실되면 <b>과금이 남은 사고 건의 복구 수단이 함께 닫힌다</b>. 이 UseCase 는
 * 유실을 막는 대신, 유실됐더라도 게이트가 열리도록 신호를 되풀이한다.
 *
 * <p><b>왜 안전한가.</b> 안전성의 근거가 이 클래스가 아니라 수신 측 도메인 계약에 있다. {@code Booking#recordRefundFailure} 는
 * REFUNDED 등 종결 상태면 아무것도 하지 않고, REFUNDING 이면 {@code failedAt} 이 기록된 값보다 나중일 때만 복원한다. 재발행이 원본 실패
 * 시각({@code refund.requestedAt})을 그대로 싣기 때문에 <b>진행 중인 재환불 시도를 중단시키지 않는다</b>.
 *
 * <p>이 보증은 {@code Booking#requestRefund()} 가 재환불을 걸 때 {@code refundFailedAt} 을 <b>지우지 않는다</b>는 데
 * 기댄다. 지우도록 바뀌면 REFUNDING 가드의 비교 대상이 사라져 재발행이 진행 중인 시도를 중단시킨다.
 *
 * <p><b>왜 반복돼도 낭비가 아닌가.</b> eventId 를 refund 별로 고정해 발행하므로, 수신 측 Inbox 가 두 번째부터 UseCase 실행 자체를 건너뛴다.
 * 그리고 재시도가 성공하면 그 FAILED 이력은 {@code Refund#markCompleted} 로 COMPLETED 가 되어 대상 집합에서 스스로 빠진다 — 정상
 * 운영에서 대상은 0 으로 수렴한다.
 *
 * <p><b>불변식: 이 UseCase 에 {@code @Transactional} 을 붙이면 안 된다.</b> {@code kafkaTemplate.send()} 는 브로커
 * 메타데이터 대기로 호출 스레드를 최대 {@code max.block.ms} 만큼 무는데, 트랜잭션 안에서 돌면 그동안 DB 커넥션을 함께 잡는다. payment 가 PG
 * 왕복을 트랜잭션 밖으로 뺀 것과 같은 이유이며({@code PaymentCancelUseCase} · {@link FailedRefundRecorder} 의 동일 불변식),
 * 브로커가 느려질 때 커넥션 풀이 마르는 사고가 Kafka 쪽에서 재현된다.
 *
 * <p><b>남는 한계.</b> {@code bookingNumber} 는 {@code refund} 에 없어 null 로 발행한다. 현재 이 토픽의 유일한 구독자인
 * booking 이 {@code bookingId}·{@code failedAt} 만 쓰기 때문에 문제가 없으나, 그 전제가 깨지면 이 경로가 먼저 깨진다. 한 refund 에
 * 대해 복구되는 유실은 1 회분뿐인 점은 ADR 0014 "남는 한계" 참고.
 */
@Slf4j
@Service
@ConditionalOnProperty(
    prefix = "app.refund-signal-replay",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class PaymentReplayRefundFailureSignalUseCase {

  /** 같은 환불 이력은 언제 재발행해도 같은 eventId 를 갖게 하는 seed 접두어. */
  private static final String EVENT_ID_SEED_PREFIX = "refund-failure-signal:";

  /**
   * 한 건도 내보내지 못한 채 이만큼 연달아 실패하면 이번 주기를 접는다.
   *
   * <p>브로커 전면 장애면 남은 건도 같은 이유로 실패할 것이 확실한데, {@code send()} 는 건당 최대 {@code max.block.ms} 를 문다. 끝까지
   * 밀면 스케줄러 스레드가 주기보다 오래 잡히고 error 로그가 배치 크기만큼 쏟아진다. 어차피 다음 주기가 같은 자리부터 다시 훑는다.
   *
   * <p><b>"성공 0건" 조건이 함께 걸린 이유</b>는 이 가드가 위치를 가리지 않으면 랩 선두의 특정 3건이 계속 실패할 때 커서가 전진도 리셋도 하지 못해 뒤쪽
   * 전체가 무기한 대기하기 때문이다. 그건 이 스케줄러가 없애려던 봉쇄와 같은 형태다. 한 건이라도 나갔다면 브로커는 살아 있다는 뜻이므로 남은 건을 계속 시도한다.
   */
  private static final int MAX_CONSECUTIVE_FAILURES = 3;

  private final RefundRepository refundRepository;
  private final EventReplayPublisher eventReplayPublisher;
  private final MeterRegistry meterRegistry;

  private final AtomicLong unresolvedCount = new AtomicLong();

  /**
   * 이번 주기가 이어서 읽을 refund_id 하한(배타).
   *
   * <p>배치 상한만 두고 매번 가장 오래된 것부터 읽으면, 미해결이 배치 크기를 넘는 순간 뒤쪽 건은 <b>영원히 선택되지 않는다</b> — FAILED 이력은 재환불이
   * 성공해야 COMPLETED 로 빠지는데 그 재환불을 여는 것이 바로 이 재발행이라 순환이 끊긴다. 커서를 들고 한 바퀴를 돌면 처음으로 되돌린다.
   *
   * <p>스케줄러 스레드 풀이 2 라 주기마다 다른 스레드가 실행될 수 있어 {@code volatile} 로 가시성을 맞춘다. 재기동하면 0 부터 다시 도는데, 순환만
   * 보장되면 되므로 영속화하지 않는다.
   */
  private volatile long cursor = 0L;

  private enum ReplayOutcome {
    REPLAYED,
    SKIPPED,
    FAILED
  }

  /**
   * 미해결(FAILED) 환불 이력 잔량 Gauge 를 등록한다.
   *
   * <p>이 값이 0 이 아니면 과금이 남은 채 환불이 성사되지 않은 건이 그만큼 있다는 뜻이다. 아래 {@code execute} 안에서만 갱신되므로 스케줄러가 멎으면
   * 마지막 값이 그대로 노출된다 — 값이 낮다고 안전 신호로 읽으면 안 된다({@code OutboxRelayService} 의 backlog 와 동일한 함정). 스케줄러를
   * 끄면 이 빈 자체가 등록되지 않아 게이지가 0 으로 박히는 대신 사라진다.
   */
  @PostConstruct
  public void registerGauge() {
    Gauge.builder(MetricNames.PAYMENT_REFUND_UNRESOLVED, unresolvedCount, AtomicLong::get)
        .register(meterRegistry);
  }

  public void execute(int batchSize) {
    unresolvedCount.set(refundRepository.countByStatus(RefundStatus.FAILED));

    List<Refund> targets =
        refundRepository.findByStatusAndIdGreaterThanOrderByIdAsc(
            RefundStatus.FAILED, cursor, PageRequest.of(0, batchSize));
    if (targets.isEmpty()) {
      cursor = 0L;
      return;
    }

    int replayed = 0;
    int consecutiveFailures = 0;
    boolean aborted = false;

    for (Refund refund : targets) {
      ReplayOutcome outcome = replay(refund);

      if (outcome == ReplayOutcome.FAILED) {
        consecutiveFailures++;
        // 한 건도 못 내보낸 채 연속으로 실패하면 브로커 쪽 전면 장애로 보고 접는다. 이미 나간 건이
        // 있다면 실패는 그 건에 국한된 사유이므로 계속 진행한다 — 여기서 접으면 뒤쪽 미해결 건이
        // 커서 전진 없이 무기한 대기한다(이 스케줄러가 애초에 없애려던 봉쇄가 위치만 바꿔 재현된다).
        if (replayed == 0 && consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
          aborted = true;
          break;
        }
        continue;
      }

      consecutiveFailures = 0;
      // SKIPPED 도 커서를 전진시킨다. 발행하지 않았더라도 그 row 는 다음 주기에 다시 봐도 같은 결과라,
      // 멈춰 서면 뒤쪽 건이 영영 선택되지 않는다.
      cursor = refund.getId();
      if (outcome == ReplayOutcome.REPLAYED) {
        replayed++;
      }
    }

    if (aborted) {
      // 커서를 옮기지 않았으므로 다음 주기가 같은 자리부터 다시 훑는다.
      log.warn("보상 신호 재발행이 연속 실패해 이번 주기를 중단합니다. 연속 실패={}건, 커서={}", consecutiveFailures, cursor);
    } else if (targets.size() < batchSize) {
      // 마지막 페이지까지 훑었으면 다음 주기가 다시 앞에서 시작하도록 되돌린다.
      cursor = 0L;
    }

    // 중단된 주기에도 그때까지 나간 건수는 남긴다. 부분 장애 구간이야말로 이 지표가 필요한 자리다.
    if (replayed > 0) {
      Counter.builder(MetricNames.PAYMENT_REFUND_SIGNAL_REPLAYED)
          .register(meterRegistry)
          .increment(replayed);
      log.info("미해결 환불 실패 보상 신호를 재발행했습니다. 재발행={}건, 미해결 총={}건", replayed, unresolvedCount.get());
    }
  }

  /** 한 건의 재발행이 실패해도 나머지가 멈추지 않도록 개별로 막는다. */
  private ReplayOutcome replay(Refund refund) {
    if (refund.getRequestedAt() == null) {
      // 실패 시각이 없으면 수신 측이 REFUNDING 진행 건과의 선후를 판정할 수 없다. 잘못된 시각을 지어내
      // 보내면 진행 중인 재환불을 중단시킬 수 있으므로 보내지 않는다. 발행 장애가 아니므로 연속 실패로도
      // 세지 않는다.
      log.warn("실패 시각이 없어 보상 신호를 재발행하지 않습니다. refundId={}", refund.getId());
      return ReplayOutcome.SKIPPED;
    }

    try {
      RefundFailedEvent event =
          new RefundFailedEvent(
              refund.getBookingId(), null, refund.getReason(), refund.getRequestedAt());
      eventReplayPublisher.republish(event, eventId(refund));
      return ReplayOutcome.REPLAYED;
    } catch (Exception e) {
      log.error(
          "보상 신호 재발행에 실패했습니다. refundId={}, bookingId={}", refund.getId(), refund.getBookingId(), e);
      return ReplayOutcome.FAILED;
    }
  }

  private String eventId(Refund refund) {
    return EventReplayPublisher.deterministicEventId(EVENT_ID_SEED_PREFIX + refund.getId());
  }
}
