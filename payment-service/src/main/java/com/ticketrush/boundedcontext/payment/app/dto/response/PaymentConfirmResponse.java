package com.ticketrush.boundedcontext.payment.app.dto.response;

import com.ticketrush.boundedcontext.payment.domain.entity.Payment;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "결제 Confirm 응답 DTO")
public record PaymentConfirmResponse(
    @Schema(description = "결제 데이터의 고유 식별자(PK)", example = "1") Long paymentId,
    @Schema(description = "결제 상태", example = "COMPLETED") String status,
    @Schema(description = "결제 완료 시각") LocalDateTime paidAt) {

  public static PaymentConfirmResponse from(Payment payment) {
    return new PaymentConfirmResponse(
        payment.getId(), payment.getStatus().name(), payment.getPaidAt());
  }
}
