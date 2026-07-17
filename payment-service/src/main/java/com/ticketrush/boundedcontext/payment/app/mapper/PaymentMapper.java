package com.ticketrush.boundedcontext.payment.app.mapper;

import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentCancelResponse;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentConfirmResponse;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentDetailResponse;
import com.ticketrush.boundedcontext.payment.app.dto.response.PaymentSummaryResponse;
import com.ticketrush.boundedcontext.payment.app.dto.response.RefundResponse;
import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

  @Mapping(source = "id", target = "paymentId")
  PaymentConfirmResponse toConfirmResponse(Payment payment);

  @Mapping(source = "id", target = "refundId")
  RefundResponse toRefundResponse(Refund refund);

  @Mapping(source = "id", target = "paymentId")
  PaymentSummaryResponse toSummaryResponse(Payment payment);

  /*
   * Payment·Refund 모두 bookingId/status를 보유해 다중 소스 매핑 시 모호하므로 payment 측을 명시한다.
   * refund가 null이면 MapStruct가 toRefundResponse(null)→null로 전파한다(환불 없는 결제 상세).
   */
  @Mapping(source = "payment.id", target = "paymentId")
  @Mapping(source = "payment.bookingId", target = "bookingId")
  @Mapping(source = "payment.status", target = "status")
  @Mapping(source = "refund", target = "refund")
  PaymentDetailResponse toDetailResponse(Payment payment, Refund refund);

  @Mapping(source = "payment.id", target = "paymentId")
  @Mapping(source = "payment.status", target = "status")
  @Mapping(source = "refund.id", target = "refundId")
  @Mapping(source = "refund.price", target = "refundedAmount")
  @Mapping(source = "refund.confirmedAt", target = "canceledAt")
  PaymentCancelResponse toCancelResponse(Payment payment, Refund refund);
}
