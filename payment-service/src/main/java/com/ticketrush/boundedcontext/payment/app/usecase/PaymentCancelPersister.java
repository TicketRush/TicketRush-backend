package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
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

    Refund saved = refundRepository.saveAndFlush(refund);
    payment.markCanceled();

    return new CancelPersisted(payment, saved);
  }

  /** 영속화 결과. {@code payment}는 CANCELED로 전이된 상태다. */
  public record CancelPersisted(Payment payment, Refund refund) {}
}
