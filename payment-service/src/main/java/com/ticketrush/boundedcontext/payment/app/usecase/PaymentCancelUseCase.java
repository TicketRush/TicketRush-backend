package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentCancelRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentCancelResponse;
import com.ticketrush.boundedcontext.payment.app.mapper.PaymentMapper;
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
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 취소(환불) UseCase.
 *
 * <p>본인 결제 검증 → 상태 검증 → PG 취소 호출 → {@link Refund} 영속화 → {@code PaymentStatus.CANCELED} 전이 → {@code
 * PaymentCanceledEvent} 발행까지의 happy path를 담당한다. 동일 결제에 대한 중복 요청은 기존 환불 내역을 반환하여 멱등하게 처리한다.
 *
 * <p>PG 취소(외부 왕복)는 트랜잭션 밖에서 호출해 DB 커넥션 장시간 점유를 피하고, 영속화(refund 저장 + 상태 전이)만 {@link
 * PaymentCancelPersister}의 짧은 트랜잭션으로 분리한다. 이벤트는 영속화 커밋 이후(트랜잭션 밖)에 발행하므로, 커밋에 성공한 데이터만 외부로 전파된다.
 *
 * <p>환불 기한 검증(공연 시작 시간 기준)과 환불 실패 시 보상 트랜잭션(#91)은 본 UseCase 범위 밖이다.
 */
@Service
@RequiredArgsConstructor
public class PaymentCancelUseCase {

  private final PaymentRepository paymentRepository;
  private final RefundRepository refundRepository;
  private final PaymentCancelClientRouter paymentCancelClientRouter;
  private final PaymentCancelPersister paymentCancelPersister;
  private final PaymentEventPublisher paymentEventPublisher;
  private final PaymentMapper paymentMapper;

  public PaymentCancelResponse execute(Long userId, Long paymentId, PaymentCancelRequest request) {
    Payment payment =
        paymentRepository
            .findByIdAndUserId(paymentId, userId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PAYMENT_NOT_FOUND));

    // 이미 취소된 결제는 기존 환불 내역을 그대로 반환하여 멱등 처리한다.
    if (payment.getStatus() == PaymentStatus.CANCELED) {
      return toExistingRefundResponse(payment, paymentId);
    }

    if (payment.getStatus() != PaymentStatus.COMPLETED) {
      throw new BusinessException(ErrorStatus.PAYMENT_NOT_CANCELABLE);
    }

    // PG 취소는 트랜잭션 밖에서 호출한다(외부 왕복 동안 DB 커넥션을 점유하지 않기 위함).
    PaymentCancelResult result =
        paymentCancelClientRouter.cancel(
            new PaymentCancelCommand(
                payment.getProvider(),
                payment.getPaymentKey(),
                payment.getAmount(),
                request.reason(),
                generateIdempotencyKey(paymentId)));

    Refund refund =
        Refund.builder()
            .paymentId(payment.getId())
            .bookingId(payment.getBookingId())
            .price(payment.getAmount())
            .status(RefundStatus.COMPLETED)
            .pgRefundKey(result.pgRefundKey())
            .reason(request.reason())
            .requestedAt(LocalDateTime.now())
            .confirmedAt(result.canceledAt())
            .build();

    // 영속화(refund 저장 + 상태 전이)만 짧은 트랜잭션으로 분리한다. 동시 취소 시 unique 위반은 PaymentFacade가 멱등 처리한다.
    CancelPersisted persisted = paymentCancelPersister.persist(paymentId, refund);

    // 발행은 영속화 커밋 이후(트랜잭션 밖)에 호출한다 → 커밋에 성공한 데이터만 전파된다.
    Payment canceled = persisted.payment();
    Refund saved = persisted.refund();
    paymentEventPublisher.publishCanceled(
        canceled.getId(),
        canceled.getBookingId(),
        canceled.getSeatId(),
        saved.getId(),
        saved.getPrice(),
        saved.getReason(),
        saved.getConfirmedAt());

    return paymentMapper.toCancelResponse(canceled, saved);
  }

  /**
   * 동시 취소 요청이 unique 제약에 막혀 별도 트랜잭션에서 먼저 환불이 확정된 경우, 그 환불 내역을 멱등하게 반환한다. PaymentFacade가 {@code
   * DataIntegrityViolationException}을 잡은 뒤 호출한다.
   */
  @Transactional(readOnly = true)
  public PaymentCancelResponse getCanceledResponse(Long userId, Long paymentId) {
    Payment payment =
        paymentRepository
            .findByIdAndUserId(paymentId, userId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PAYMENT_NOT_FOUND));
    return toExistingRefundResponse(payment, paymentId);
  }

  private PaymentCancelResponse toExistingRefundResponse(Payment payment, Long paymentId) {
    Refund existing =
        refundRepository
            .findByPaymentId(paymentId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PAYMENT_REFUND_INCONSISTENT));
    return paymentMapper.toCancelResponse(payment, existing);
  }

  /* 동일 결제의 재요청 시 PG 측 중복 취소를 막기 위해 paymentId 기반 고정 멱등 키를 생성한다. */
  private String generateIdempotencyKey(Long paymentId) {
    return "REFUND-%07d".formatted(paymentId);
  }
}
