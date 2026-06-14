package com.ticketrush.boundedcontext.payment.app.facade;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentCancelRequest;
import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentCancelResponse;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentDetailResponse;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentSummaryResponse;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentCancelUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentConfirmUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentGetDetailUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentGetListUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentFacade {

  private final PaymentConfirmUseCase paymentConfirmUseCase;
  private final PaymentCancelUseCase paymentCancelUseCase;
  private final PaymentGetListUseCase paymentGetListUseCase;
  private final PaymentGetDetailUseCase paymentGetDetailUseCase;

  public PaymentConfirmResponse confirm(Long userId, PaymentConfirmRequest request) {
    return paymentConfirmUseCase.execute(userId, request);
  }

  public PaymentCancelResponse cancel(Long userId, Long paymentId, PaymentCancelRequest request) {
    return paymentCancelUseCase.execute(userId, paymentId, request);
  }

  public Page<PaymentSummaryResponse> getPayments(Long userId, Pageable pageable) {
    return paymentGetListUseCase.execute(userId, pageable);
  }

  public PaymentDetailResponse getPayment(Long userId, Long paymentId) {
    return paymentGetDetailUseCase.execute(userId, paymentId);
  }
}
