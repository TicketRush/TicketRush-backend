package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentDetailResponse;
import com.ticketrush.boundedcontext.payment.app.mapper.PaymentMapper;
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

@Service
@RequiredArgsConstructor
public class PaymentGetDetailUseCase {

  private final PaymentRepository paymentRepository;
  private final RefundRepository refundRepository;
  private final PaymentMapper paymentMapper;

  @Transactional(readOnly = true)
  public PaymentDetailResponse execute(Long userId, Long paymentId) {
    /* 미존재와 소유권 위반을 동일 응답으로 통일해 다른 사용자 결제 ID 존재 여부 노출을 막는다. */
    Payment payment =
        paymentRepository
            .findByIdAndUserId(paymentId, userId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PAYMENT_NOT_FOUND));

    // COMPLETED 환불만 상세에 노출한다. #334로 COMPLETED 결제에도 FAILED 환불 이력이 남을 수 있어, 상태 무관 매핑 시
    // 정상 결제 상세에 "환불 실패" 정보가 잘못 노출된다.
    Refund refund =
        refundRepository
            .findByBookingId(payment.getBookingId())
            .filter(r -> r.getStatus() == RefundStatus.COMPLETED)
            .orElse(null);

    return paymentMapper.toDetailResponse(payment, refund);
  }
}
