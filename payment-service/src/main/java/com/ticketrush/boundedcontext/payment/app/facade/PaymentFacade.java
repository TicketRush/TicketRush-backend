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
import com.ticketrush.boundedcontext.payment.app.usecase.PaymentWebhookUseCase;
import com.ticketrush.boundedcontext.payment.app.usecase.RegisterExpiredBookingUseCase;
import com.ticketrush.global.exception.BusinessException;
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
  private final PaymentWebhookUseCase paymentWebhookUseCase;

  public PaymentConfirmResponse confirm(Long userId, PaymentConfirmRequest request) {
    try {
      return paymentConfirmUseCase.execute(userId, request);
    } catch (DataIntegrityViolationException e) {
      // 동시 confirm 요청이 unique 제약에 막힌 경우, 먼저 확정된 결제를 멱등 반환한다. bookingId 조회는 paymentKey 충돌과
      // 동일 booking·다른 paymentKey 충돌(uk_payment_completed_booking, #296)을 모두 흡수하는 상위집합이다. 유니크
      // 충돌이 아닌 다른 무결성 위반이라 조회되지 않으면, 원인을 숨기지 않도록 원래 예외를 재던진다.
      try {
        return paymentConfirmUseCase.getConfirmedResponseByBookingId(request.bookingId());
      } catch (BusinessException lookupFailure) {
        throw e;
      }
    }
  }

  public void handleWebhook(byte[] rawBody) {
    paymentWebhookUseCase.handle(rawBody);
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
