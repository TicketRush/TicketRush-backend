package com.ticketrush.boundedcontext.payment.app.dto.response;

import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import com.ticketrush.boundedcontext.payment.domain.entity.Refund;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "결제 내역 단건 상세 응답 DTO")
public record PaymentDetailResponse(
    @Schema(description = "결제 데이터의 고유 식별자(PK)", example = "1") Long paymentId,
    @Schema(description = "예매 ID", example = "12") Long bookingId,
    @Schema(description = "결제 수단", example = "TOSS") PaymentProvider provider,
    @Schema(description = "결제 금액", example = "55000") Long amount,
    @Schema(description = "결제 상태", example = "COMPLETED") PaymentStatus status,
    @Schema(description = "결제 완료 시각") LocalDateTime paidAt,
    @Schema(description = "PG사 발급 결제 키") String paymentKey,
    @Schema(description = "PG사 응답 승인 번호") String approvalNumber,
    @Schema(description = "환불 정보 (환불이 있는 경우에만 포함)") RefundResponse refund) {

  public static PaymentDetailResponse of(Payment payment, Refund refund) {
    return new PaymentDetailResponse(
        payment.getId(),
        payment.getBookingId(),
        payment.getProvider(),
        payment.getAmount(),
        payment.getStatus(),
        payment.getPaidAt(),
        payment.getPaymentKey(),
        payment.getApprovalNumber(),
        refund == null ? null : RefundResponse.from(refund));
  }
}
