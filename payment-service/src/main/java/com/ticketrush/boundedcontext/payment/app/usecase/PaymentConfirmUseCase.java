package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
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
 * <p>쓰기가 {@code paymentRepository.save} 단건뿐이라 여러 문장을 묶는 트랜잭션이 필요 없다. 따라서 PG 승인(외부 왕복)을 트랜잭션 안에 가두지
 * 않아 DB 커넥션을 장시간 점유하지 않는다. 이벤트는 save(자체 트랜잭션 커밋) 성공 이후에 호출되므로, 커밋에 성공한 데이터만 외부로 전파된다.
 */
@Service
@RequiredArgsConstructor
public class PaymentConfirmUseCase {

  private final PaymentRepository paymentRepository;
  private final PaymentApprovalClientRouter paymentApprovalClientRouter;
  private final PaymentEventPublisher paymentEventPublisher;
  private final ExpiredBookingRepository expiredBookingRepository;

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

    Payment saved = paymentRepository.save(payment);

    paymentEventPublisher.publishConfirmed(
        saved.getId(),
        saved.getBookingId(),
        request.seatId(),
        userId,
        saved.getAmount(),
        saved.getPaidAt());

    return PaymentConfirmResponse.from(saved);
  }

  /* PG 공통 orderId 규격(6~64자, 영문/숫자/_/-, 현재 Toss 기준)을 만족하기 위해 zero-padding 한다. */
  private String generateOrderId(Long bookingId) {
    return "BKG-%07d".formatted(bookingId);
  }
}
