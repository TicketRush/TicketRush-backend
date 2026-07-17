package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.boundedcontext.payment.out.repository.RefundRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 취소의 영속화(환불 저장 + 결제 상태 전이)만 짧은 트랜잭션으로 묶는 컴포넌트.
 *
 * <p>PG 취소 호출은 {@link PaymentCancelUseCase}가 트랜잭션 밖에서 수행하고, 두 쓰기(refund 저장 + {@link
 * Payment#markCanceled()})만 본 컴포넌트의 트랜잭션 안에서 원자적으로 처리한다. 외부 PG 왕복 동안 DB 커넥션을 점유하지 않기 위함이다.
 *
 * <p>self-invocation 프록시 한계 때문에 영속화를 UseCase와 별도 빈으로 분리했다.
 */
@Service
@RequiredArgsConstructor
public class PaymentCancelPersister {

  private final PaymentRepository paymentRepository;
  private final RefundRepository refundRepository;

  /**
   * 환불을 저장하고 결제를 CANCELED로 전이한다.
   *
   * <p>{@code paymentId}만으로 결제를 재조회하므로 본인 결제 검증(인가)을 수행하지 않는다. <b>반드시 호출자({@link
   * PaymentCancelUseCase})가 {@code userId} 기반 인가·상태 검증을 마친 뒤에만 호출해야 한다.</b>
   *
   * <p>트랜잭션 안에서 결제를 다시 조회해 managed 상태로 만든 뒤 상태를 전이한다(호출자가 트랜잭션 밖에서 읽은 엔티티는 detached이기 때문). {@code
   * saveAndFlush}를 먼저 호출해 동시 취소 시 paymentId unique 위반을 상태 전이/이벤트 발행 이전에 표면화한다. 위반({@code
   * DataIntegrityViolationException})은 트랜잭션 경계 밖({@link
   * com.ticketrush.boundedcontext.payment.app.facade.PaymentFacade})에서 멱등 처리한다.
   */
  @Transactional
  public CancelPersisted persist(Long paymentId, Refund refund) {
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PAYMENT_NOT_FOUND));

    // 이전 시도에서 FAILED 환불 이력이 있으면(재시도 성공, #334) unique(payment_id) 슬롯을 재사용하도록 새 INSERT 대신 전이한다.
    // 이 선-조회가 없으면 FAILED row와 unique 충돌이 나고, 그 DataIntegrityViolationException을 상위(Facade/리스너)가
    // "이미 환불됨(멱등)"으로 오분류해 실제 성공한 환불이 은폐된다. 동시 최초 환불의 경합은 여전히 아래 saveAndFlush에서 표면화된다.
    Refund existingFailed =
        refundRepository
            .findByPaymentId(paymentId)
            .filter(r -> r.getStatus() == RefundStatus.FAILED)
            .orElse(null);

    Refund saved;
    if (existingFailed != null) {
      existingFailed.markCompleted(
          refund.getPgRefundKey(), refund.getReason(), refund.getConfirmedAt());
      saved = existingFailed;
    } else {
      saved = refundRepository.saveAndFlush(refund);
    }
    payment.markCanceled();

    return new CancelPersisted(payment, saved);
  }

  /**
   * 실패(FAILED) 환불 이력만 저장한다(#334). 결제는 취소되지 않았으므로 {@code markCanceled}는 호출하지 않는다(Payment는 COMPLETED
   * 유지).
   *
   * <p>{@code saveAndFlush}로 즉시 flush해 {@code payment_id} unique 위반({@code
   * DataIntegrityViolationException})을 트랜잭션 경계 밖(호출자 {@link FailedRefundRecorder})에서 멱등 처리할 수 있게
   * 한다. 재전달/DLT 재처리로 이미 FAILED 이력이 있으면 위반이 난다.
   */
  @Transactional
  public void persistFailedRefund(Refund failedRefund) {
    refundRepository.saveAndFlush(failedRefund);
  }

  /** 영속화 결과. {@code payment}는 CANCELED로 전이된 상태다. */
  public record CancelPersisted(Payment payment, Refund refund) {}
}
