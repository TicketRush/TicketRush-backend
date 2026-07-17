package com.ticketrush.boundedcontext.payment.app.usecase;

import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentSummaryResponse;
import com.ticketrush.boundedcontext.payment.app.mapper.PaymentMapper;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import com.ticketrush.boundedcontext.payment.out.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentGetListUseCase {

  private final PaymentRepository paymentRepository;
  private final PaymentMapper paymentMapper;

  @Transactional(readOnly = true)
  public Page<PaymentSummaryResponse> execute(Long userId, Pageable pageable) {
    return paymentRepository
        .findByUserIdAndStatus(userId, PaymentStatus.COMPLETED, pageable)
        .map(paymentMapper::toSummaryResponse);
  }
}
