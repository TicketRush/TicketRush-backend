package com.ticketrush.boundedcontext.payment.app.support;

import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.shared.payment.event.PaymentCanceledEvent;
import com.ticketrush.shared.payment.event.PaymentConfirmedEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

  private final EventPublisher eventPublisher;

  public void publishConfirmed(
      Long paymentId, Long bookingId, Long seatId, Long userId, Long amount, LocalDateTime paidAt) {
    eventPublisher.publish(
        new PaymentConfirmedEvent(paymentId, bookingId, seatId, userId, amount, paidAt));
  }

  public void publishCanceled(
      Long paymentId,
      Long bookingId,
      Long seatId,
      Long refundId,
      Long refundedAmount,
      String reason,
      LocalDateTime canceledAt) {
    eventPublisher.publish(
        new PaymentCanceledEvent(
            paymentId, bookingId, seatId, refundId, refundedAmount, reason, canceledAt));
  }
}
