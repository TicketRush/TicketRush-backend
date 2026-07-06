package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentCancelPersister.CancelPersisted;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelClientRouter;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelCommand;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentCancelResult;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.boundedcontext.payment.out.repository.RefundRepository;
import com.ticketrush.global.constants.MetricNames;
import com.ticketrush.shared.booking.event.RefundRequestedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 예매 취소로 발행된 {@link RefundRequestedEvent}를 받아 PG 환불을 실행하는 UseCase (#91, 이벤트 기반 정방향 경로).
 *
 * <p>결제 취소 API(#22, {@link PaymentCancelUseCase})와 환불 실행(PG 취소 → {@link Refund} 영속화 → {@code
 * PaymentStatus.CANCELED} 전이 → {@code PaymentCanceledEvent} 발행) 구조는 동일하되, 진입점이 {@code
 * userId+paymentId}가 아니라 {@code bookingId}다. 성공 이벤트에 {@code bookingNumber}를 실어 좌석 소유 교차검증(ABA 방지)을
 * 가능하게 한다.
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

  private static final String REFUND_REASON = "사용자 예매 취소";

  private final PaymentRepository paymentRepository;
  private final RefundRepository refundRepository;
  private final PaymentCancelClientRouter paymentCancelClientRouter;
  private final PaymentCancelPersister paymentCancelPersister;
  private final PaymentEventPublisher paymentEventPublisher;
  private final MeterRegistry meterRegistry;

  public enum RefundOutcome {
    /** PG 환불을 실행하고 {@code PaymentCanceledEvent}를 발행했다. */
    REFUNDED,
    /** 이미 환불된 건이라 커밋된 환불 데이터로 {@code PaymentCanceledEvent}를 재발행했다(발행 유실 self-heal). */
    REPUBLISHED,
    /** 대상 결제가 없어 처리를 건너뛰었다(멱등). */
    ALREADY_SETTLED
  }

  public RefundOutcome execute(RefundRequestedEvent event) {
    Payment payment =
        paymentRepository
            .findFirstByBookingIdAndStatus(event.bookingId(), PaymentStatus.COMPLETED)
            .orElse(null);

    // 완료된 결제가 없으면 이미 환불(CANCELED)됐거나 결제 자체가 없는 경우다. 전자는 발행 유실 대비 재발행으로 self-heal 한다.
    if (payment == null) {
      return republishIfAlreadyRefunded(event);
    }

    // PG 취소는 트랜잭션 밖에서 호출한다(외부 왕복 동안 DB 커넥션을 점유하지 않기 위함). paymentId 기반 고정 멱등 키로 PG 측 중복 취소를 막는다.
    Timer.Sample sample = Timer.start(meterRegistry);
    PaymentCancelResult result;
    try {
      result =
          paymentCancelClientRouter.cancel(
              new PaymentCancelCommand(
                  payment.getProvider(),
                  payment.getPaymentKey(),
                  payment.getAmount(),
                  REFUND_REASON,
                  generateIdempotencyKey(payment.getId())));
    } finally {
      sample.stop(
          Timer.builder(MetricNames.PAYMENT_PG_CANCEL)
              .tag(MetricNames.TAG_PROVIDER, payment.getProvider().name())
              .register(meterRegistry));
    }

    Refund refund =
        Refund.builder()
            .paymentId(payment.getId())
            .bookingId(payment.getBookingId())
            .price(payment.getAmount())
            .status(RefundStatus.COMPLETED)
            .pgRefundKey(result.pgRefundKey())
            .reason(REFUND_REASON)
            .requestedAt(LocalDateTime.now())
            .confirmedAt(result.canceledAt())
            .build();

    // 영속화(refund 저장 + 상태 전이)만 짧은 트랜잭션으로 분리한다. 동시 환불 시 unique 위반은 리스너가 멱등 처리한다(#296).
    CancelPersisted persisted = paymentCancelPersister.persist(payment.getId(), refund);

    // 발행은 영속화 커밋 이후(트랜잭션 밖)에 호출한다 → 커밋에 성공한 데이터만 전파된다. bookingNumber로 좌석 소유 교차검증을 가능케 한다.
    Payment canceled = persisted.payment();
    Refund saved = persisted.refund();
    paymentEventPublisher.publishCanceled(
        canceled.getId(),
        canceled.getBookingId(),
        event.bookingNumber(),
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
  private RefundOutcome republishIfAlreadyRefunded(RefundRequestedEvent event) {
    Payment canceled =
        paymentRepository
            .findFirstByBookingIdAndStatus(event.bookingId(), PaymentStatus.CANCELED)
            .orElse(null);
    if (canceled == null) {
      return RefundOutcome.ALREADY_SETTLED;
    }

    Refund refund = refundRepository.findByBookingId(event.bookingId()).orElse(null);
    if (refund == null) {
      // CANCELED인데 환불 레코드가 없다 = 재발행할 데이터가 없는 비정상. 스킵한다(재발행 불가).
      log.warn("환불 재발행 스킵: CANCELED 결제에 환불 레코드가 없습니다. bookingId: {}", event.bookingId());
      return RefundOutcome.ALREADY_SETTLED;
    }

    paymentEventPublisher.publishCanceled(
        canceled.getId(),
        canceled.getBookingId(),
        event.bookingNumber(),
        canceled.getSeatId(),
        refund.getId(),
        refund.getPrice(),
        refund.getReason(),
        refund.getConfirmedAt());
    return RefundOutcome.REPUBLISHED;
  }

  /* 동일 결제의 재요청 시 PG 측 중복 취소를 막기 위해 paymentId 기반 고정 멱등 키를 생성한다(API 취소 경로와 동일 규칙). */
  private String generateIdempotencyKey(Long paymentId) {
    return "REFUND-%07d".formatted(paymentId);
  }
}
