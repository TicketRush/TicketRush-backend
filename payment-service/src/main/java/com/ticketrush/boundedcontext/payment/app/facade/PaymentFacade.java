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
import com.ticketrush.boundedcontext.payment.app.usecase.RegisterExpiredBookingUseCase;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
  private final RegisterExpiredBookingUseCase registerExpiredBookingUseCase;

  public PaymentConfirmResponse confirm(Long userId, PaymentConfirmRequest request) {
    return paymentConfirmUseCase.execute(userId, request);
  }

  public void registerExpiredBooking(Long bookingId, LocalDateTime expiredAt) {
    registerExpiredBookingUseCase.execute(bookingId, expiredAt);
  }

  public PaymentCancelResponse cancel(Long userId, Long paymentId, PaymentCancelRequest request) {
    try {
      return paymentCancelUseCase.execute(userId, paymentId, request);
    } catch (DataIntegrityViolationException e) {
      // 동시 취소 요청이 paymentId unique 제약에 막힌 경우, 먼저 확정된 환불을 멱등 반환한다.
      return paymentCancelUseCase.getCanceledResponse(userId, paymentId);
    }
  }

  public Page<PaymentSummaryResponse> getPayments(Long userId, Pageable pageable) {
    return paymentGetListUseCase.execute(userId, pageable);
  }

  public PaymentDetailResponse getPayment(Long userId, Long paymentId) {
    return paymentGetDetailUseCase.execute(userId, paymentId);
  }
}
