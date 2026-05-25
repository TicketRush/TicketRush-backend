package com.ticketrush.boundedcontext.payment.app.facade;

import com.ticketrush.boundedcontext.payment.app.dto.request.PaymentConfirmRequest;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentConfirmUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentFacade {

  private final PaymentConfirmUseCase paymentConfirmUseCase;

  public PaymentConfirmResponse confirm(Long userId, PaymentConfirmRequest request) {
    return paymentConfirmUseCase.execute(userId, request);
  }
}
