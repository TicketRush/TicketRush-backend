package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.mapper.PaymentMapper;
import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalClientRouter;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalRequest;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalResponse;
import com.ticketrush.boundedcontext.payment.out.repository.ExpiredBookingRepository;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 결제 승인(confirm) UseCase.
 *
 * <p>쓰기가 {@code paymentRepository.saveAndFlush} 단건뿐이라 여러 문장을 묶는 트랜잭션이 필요 없다. 따라서 PG 승인(외부 왕복)을 트랜잭션
 * 안에 가두지 않아 DB 커넥션을 장시간 점유하지 않는다. 이벤트는 saveAndFlush(자체 트랜잭션 커밋) 성공 이후에 호출되므로, 커밋에 성공한 데이터만 외부로
 * 전파된다. (ID 전략이 IDENTITY라 {@code save}만으로도 INSERT가 즉시 실행되지만, 동시 confirm 시 unique 위반을 이벤트 발행 이전에
 * 표면화한다는 의도를 명시하고 취소 경로({@code PaymentCancelPersister})와 일관성을 맞추기 위해 saveAndFlush를 쓴다, #296.)
 */
@Service
@RequiredArgsConstructor
public class PaymentConfirmUseCase {

  private final PaymentRepository paymentRepository;
  private final PaymentApprovalClientRouter paymentApprovalClientRouter;
  private final PaymentEventPublisher paymentEventPublisher;
  private final ExpiredBookingRepository expiredBookingRepository;
  private final PaymentMapper paymentMapper;

  public PaymentConfirmResponse execute(Long userId, PaymentConfirmRequest request) {
    if (paymentRepository.existsByBookingIdAndStatus(
        request.bookingId(), PaymentStatus.COMPLETED)) {
      throw new BusinessException(ErrorStatus.PAYMENT_ALREADY_COMPLETED);
    }

    // 만료 이벤트를 이미 수신한 booking이면 PG 호출 전 차단한다(best-effort 방어선, #224).
    if (expiredBookingRepository.existsByBookingId(request.bookingId())) {
      throw new BusinessException(ErrorStatus.BOOKING_EXPIRED);
    }

    String orderId = generateOrderId(request.bookingId());

    PaymentApprovalResponse approval =
        paymentApprovalClientRouter.approve(
            new PaymentApprovalRequest(
                request.provider(),
                request.paymentKey(),
                orderId,
                request.bookingId(),
                request.amount()));

    if (!request.amount().equals(approval.approvedAmount())) {
      throw new BusinessException(ErrorStatus.PAYMENT_AMOUNT_MISMATCH);
    }

    Payment payment =
        Payment.builder()
            .bookingId(request.bookingId())
            .userId(userId)
            .seatId(request.seatId())
            .provider(request.provider())
            .amount(approval.approvedAmount())
            .status(PaymentStatus.COMPLETED)
            .paymentKey(request.paymentKey())
            .approvalNumber(approval.approvalNumber())
            .paidAt(approval.approvedAt())
            .build();

    // 동일 booking 동시 confirm 시 uk_payment_completed_booking unique 위반을 이벤트 발행 이전에 표면화하기 위해
    // saveAndFlush로 INSERT를 flush한다. 위반(DataIntegrityViolationException)은 PaymentFacade가 멱등
    // 처리한다(#296).
    Payment saved = paymentRepository.saveAndFlush(payment);

    paymentEventPublisher.publishConfirmed(
        saved.getId(),
        saved.getBookingId(),
        request.seatId(),
        userId,
        saved.getAmount(),
        saved.getPaidAt());

    return paymentMapper.toConfirmResponse(saved);
  }

  /**
   * 이미 확정된 결제를 bookingId로 조회해 멱등 응답으로 반환한다.
   *
   * <p>동시 confirm 요청이 unique 제약에 막혔을 때, 먼저 확정된 COMPLETED 결제를 돌려주기 위해 {@link
   * com.ticketrush.boundedcontext.payment.app.facade.PaymentFacade}의 멱등 fallback에서 호출한다. bookingId
   * 기준 조회는 paymentKey 충돌의 상위집합이라, 동일 paymentKey 재수신과 동일 booking·다른 paymentKey 충돌을 모두 흡수한다(#296).
   */
  public PaymentConfirmResponse getConfirmedResponseByBookingId(Long bookingId) {
    Payment payment =
        paymentRepository
            .findFirstByBookingIdAndStatus(bookingId, PaymentStatus.COMPLETED)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PAYMENT_NOT_FOUND));
    return paymentMapper.toConfirmResponse(payment);
  }

  /* PG 공통 orderId 규격(6~64자, 영문/숫자/_/-, 현재 Toss 기준)을 만족하기 위해 zero-padding 한다. */
  private String generateOrderId(Long bookingId) {
    return "BKG-%07d".formatted(bookingId);
  }
}
