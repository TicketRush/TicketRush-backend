package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.support.PaymentEventPublisher;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalClient;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalRequest;
import com.ticketrush.boundedcontext.payment.out.apiclient.PaymentApprovalResponse;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class PaymentConfirmUseCase {

  private final PaymentRepository paymentRepository;
  private final PaymentApprovalClient paymentApprovalClient;
  private final PaymentEventPublisher paymentEventPublisher;

  public PaymentConfirmResponse execute(Long userId, PaymentConfirmRequest request) {
    if (paymentRepository.existsByBookingIdAndStatus(
        request.bookingId(), PaymentStatus.COMPLETED)) {
      throw new BusinessException(ErrorStatus.PAYMENT_ALREADY_COMPLETED);
    }

    PaymentApprovalResponse approval =
        paymentApprovalClient.approve(
            new PaymentApprovalRequest(
                request.provider(), request.paymentKey(), request.bookingId(), request.amount()));

    if (!request.amount().equals(approval.approvedAmount())) {
      throw new BusinessException(ErrorStatus.PAYMENT_AMOUNT_MISMATCH);
    }

    Payment payment =
        Payment.builder()
            .bookingId(request.bookingId())
            .provider(request.provider())
            .amount(approval.approvedAmount())
            .status(PaymentStatus.COMPLETED)
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
}
