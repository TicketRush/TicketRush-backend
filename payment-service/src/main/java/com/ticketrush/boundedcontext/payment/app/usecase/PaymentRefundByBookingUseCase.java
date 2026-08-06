package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentCancelPersister.CancelPersisted;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.domain.types.RefundTrigger;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelClientRouter;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelCommand;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelResult;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.boundedcontext.payment.out.repository.RefundRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.global.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@code bookingId}를 진입점으로 PG 환불을 실행하는 UseCase (#91).
 *
 * <p>결제 취소 API(#22, {@link PaymentCancelUseCase})와 환불 실행(PG 취소 → {@link Refund} 영속화 → {@code
 * PaymentStatus.CANCELED} 전이 → {@code PaymentCanceledEvent} 발행) 구조는 동일하되, 진입점이 {@code
 * userId+paymentId}가 아니라 {@code bookingId}다. 성공 이벤트에 {@code bookingNumber}를 실어 좌석 소유 교차검증(ABA 방지)을
 * 가능하게 한다.
 *
 * <p>두 경로가 이 UseCase를 공유한다 — 예매 취소 Saga({@code RefundRequestedEvent}, #91)와 좌석 확정 실패 보상({@code
 * SeatConfirmFailedEvent}, #492)이다. 어느 쪽이 유발했는지는 {@link RefundTrigger}로 받는다. 이벤트 타입을 직접 받지 않는 것은
 * 실제로 쓰는 값이 {@code bookingId}와 {@code bookingNumber} 둘뿐이라 타입 결합에 실체가 없고, 사고 보상 건이 "사용자 취소"로 기록되면 PG
 * 관리자 화면과 정산 분석에서 사고 건수를 셀 수 없기 때문이다.
 *
 * <p>대상 결제가 없으면 {@link RefundOutcome#ALREADY_SETTLED}로 멱등 스킵한다. 이미 환불(CANCELED)됐는데 재전달됐다면 커밋된 환불
 * 데이터로 {@code PaymentCanceledEvent}를 <b>재발행</b>({@link RefundOutcome#REPUBLISHED})해, 발행 유실로
 * booking이 REFUNDING·좌석이 SOLD로 고착되는 것을 self-heal 한다. PG 취소 실패는 호출한 리스너가 영구/일시로 분류해 보상({@code
 * RefundFailedEvent}) 또는 재시도로 처리하도록 예외를 전파한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRefundByBookingUseCase {

  private final PaymentRepository paymentRepository;
  private final RefundRepository refundRepository;
  private final PaymentCancelClientRouter paymentCancelClientRouter;
  private final PaymentCancelPersister paymentCancelPersister;
  private final FailedRefundRecorder failedRefundRecorder;
  private final PaymentEventPublisher paymentEventPublisher;
  private final MeterRegistry meterRegistry;

  public enum RefundOutcome {
    /** PG 환불을 실행하고 {@code PaymentCanceledEvent}를 발행했다. */
    REFUNDED,
    /** 이미 환불된 건이라 커밋된 환불 데이터로 {@code PaymentCanceledEvent}를 재발행했다(발행 유실 self-heal). */
    REPUBLISHED,
    /**
     * 대상 결제가 없어 처리를 건너뛰었다(멱등).
     *
     * <p>취소 요청 경로(#91)에서는 이미 정리된 건에 이벤트가 재전달된 정상 상황이다. 그러나 보상 경로(#492)에서는 "과금됐다"가 신호의 전제이므로 같은 값이
     * 전제 붕괴를 뜻한다 — 호출자가 경로에 맞는 심각도로 해석해야 한다.
     */
    ALREADY_SETTLED
  }

  public RefundOutcome execute(Long bookingId, String bookingNumber, RefundTrigger trigger) {
    Payment payment =
        paymentRepository
            .findFirstByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED)
            .orElse(null);

    // 완료된 결제가 없으면 이미 환불(CANCELED)됐거나 결제 자체가 없는 경우다. 전자는 발행 유실 대비 재발행으로 self-heal 한다.
    if (payment == null) {
      return republishIfAlreadyRefunded(bookingId, bookingNumber);
    }

    // PG 취소는 트랜잭션 밖에서 호출한다(외부 왕복 동안 DB 커넥션을 점유하지 않기 위함). paymentId 기반 고정 멱등 키로 PG 측 중복 취소를 막는다.
    Timer.Sample sample = Timer.start(meterRegistry);
    PaymentCancelResult result = null;
    BusinessException failure = null;
    try {
      result =
          paymentCancelClientRouter.cancel(
              new PaymentCancelCommand(
                  payment.getProvider(),
                  payment.getPaymentKey(),
                  payment.getAmount(),
                  trigger.getReason(),
                  generateIdempotencyKey(payment.getId())));
    } catch (BusinessException e) {
      failure = e;
    } finally {
      // 타이머는 PG 왕복만 측정하도록 FAILED 저장(recordIfRejected)보다 먼저 멈춘다. 다만 그 "왕복"에는
      // 클라이언트 내부 재시도(#573 — 대기 후 최대 1회 재호출)가 포함되므로, 재시도가 발생한 건은 대기 +
      // 2차 왕복만큼 길어진다(최악 +11초 = 대기 1초 + read-timeout 10초). p99 상승 구간의 원인은
      // PAYMENT_PG_CANCEL_RETRY 카운터와 겹쳐 읽는다.
      sample.stop(
          Timer.builder(MetricNames.PAYMENT_PG_CANCEL)
              .tag(MetricNames.TAG_PROVIDER, payment.getProvider().name())
              .register(meterRegistry));
    }
    if (failure != null) {
      // PG 거절이면 FAILED 환불 이력을 남긴다(부수효과). 원 예외는 그대로 재던져 리스너가 보상/재시도로 분류하게 한다(#91, #334).
      failedRefundRecorder.recordIfRejected(payment, failure);
      throw failure;
    }

    Refund refund =
        Refund.completed(
            payment.getId(),
            payment.getBookingId(),
            payment.getAmount(),
            result.pgRefundKey(),
            trigger.getReason(),
            LocalDateTime.now(),
            result.canceledAt());

    // 영속화(refund 저장 + 상태 전이)만 짧은 트랜잭션으로 분리한다. 동시 환불 시 unique 위반은 리스너가 멱등 처리한다(#296).
    CancelPersisted persisted = paymentCancelPersister.persist(payment.getId(), refund);

    // 발행은 영속화 커밋 이후(트랜잭션 밖)에 호출한다 → 커밋에 성공한 데이터만 전파된다. bookingNumber로 좌석 소유 교차검증을 가능케 한다.
    Payment canceled = persisted.payment();
    Refund saved = persisted.refund();
    paymentEventPublisher.publishCanceled(
        canceled.getId(),
        canceled.getBookingId(),
        bookingNumber,
        canceled.getSeatId(),
        saved.getId(),
        saved.getPrice(),
        saved.getReason(),
        saved.getConfirmedAt());

    return RefundOutcome.REFUNDED;
  }

  /**
   * 완료 결제가 없을 때, 이미 환불(CANCELED)된 건이면 커밋된 환불 데이터로 {@code PaymentCanceledEvent}를 재발행한다.
   *
   * <p>최초 처리에서 환불은 커밋됐으나 발행이 유실(비동기 send 실패 등)돼 booking이 REFUNDING·좌석이 SOLD로 남은 경우, 재전달 시 이 재발행으로
   * 정합을 self-heal 한다. 소비자는 멱등이라 정상 처리된 건에 재전달돼도 안전하다. 결제 자체가 없으면 멱등 스킵한다.
   */
  private RefundOutcome republishIfAlreadyRefunded(Long bookingId, String bookingNumber) {
    Payment canceled =
        paymentRepository
            .findFirstByBookingIdAndStatus(bookingId, PaymentStatus.CANCELED)
            .orElse(null);
    if (canceled == null) {
      return RefundOutcome.ALREADY_SETTLED;
    }

    Refund refund = refundRepository.findByBookingId(bookingId).orElse(null);
    if (refund == null) {
      // CANCELED인데 환불 레코드가 없다 = 재발행할 데이터가 없는 비정상. 스킵한다(재발행 불가).
      log.warn("환불 재발행 스킵: CANCELED 결제에 환불 레코드가 없습니다. bookingId: {}", bookingId);
      return RefundOutcome.ALREADY_SETTLED;
    }

    paymentEventPublisher.publishCanceled(
        canceled.getId(),
        canceled.getBookingId(),
        bookingNumber,
        canceled.getSeatId(),
        refund.getId(),
        refund.getPrice(),
        refund.getReason(),
        refund.getConfirmedAt());
    return RefundOutcome.REPUBLISHED;
  }

  /*
   * 동일 결제의 재요청 시 PG 측 중복 취소를 막기 위해 paymentId 기반 고정 멱등 키를 생성한다(API 취소 경로와 동일 규칙).
   *
   * 트리거가 달라도 키를 나누지 않는다. #492 이후 같은 결제에 서로 다른 reason 이 실릴 수 있지만, Toss 는 멱등키와
   * API 키·주소·HTTP 메서드 조합으로만 동일 요청을 판정하고 body 는 검증 기준이 아니다(공식 문서 확인, #492). 즉
   * "같은 키·다른 body"가 거절을 유발하지 않는다. 반대로 키를 트리거별로 가르면 두 경로가 같은 결제를 각자의 키로
   * 취소해 PG 중복 취소 방어가 통째로 풀린다 — 이중 환불이 훨씬 무겁다.
   */
  private String generateIdempotencyKey(Long paymentId) {
    return "REFUND-%07d".formatted(paymentId);
  }
}
