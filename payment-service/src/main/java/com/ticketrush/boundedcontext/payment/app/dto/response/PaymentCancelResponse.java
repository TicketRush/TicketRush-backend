package com.ticketrush.boundedcontext.payment.app.dto.response;

import com.ticketrush.global.json.UtcLocalDateTimeSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

@Schema(description = "결제 취소(환불) 응답 DTO")
public record PaymentCancelResponse(
    @Schema(description = "결제 데이터의 고유 식별자(PK)", example = "1") Long paymentId,
    @Schema(description = "결제 상태", example = "CANCELED") String status,
    @Schema(description = "환불 데이터의 고유 식별자(PK)", example = "1") Long refundId,
    @Schema(description = "환불 금액", example = "55000") Long refundedAmount,
    @Schema(description = "환불 확정 시각") @JsonSerialize(using = UtcLocalDateTimeSerializer.class)
        LocalDateTime canceledAt) {}
