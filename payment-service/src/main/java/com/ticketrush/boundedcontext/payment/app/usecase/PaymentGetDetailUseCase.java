package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentDetailResponse;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
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

  @Transactional(readOnly = true)
  public PaymentDetailResponse execute(Long userId, Long paymentId) {
    /* 미존재와 소유권 위반을 동일 응답으로 통일해 다른 사용자 결제 ID 존재 여부 노출을 막는다. */
    Payment payment =
        paymentRepository
            .findByIdAndUserId(paymentId, userId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PAYMENT_NOT_FOUND));

    Refund refund = refundRepository.findByBookingId(payment.getBookingId()).orElse(null);

    return PaymentDetailResponse.of(payment, refund);
  }
}
