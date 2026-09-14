package com.ticketrush.boundedcontext.payment.app.dto.response;

import com.ticketrush.global.json.UtcLocalDateTimeSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

@Schema(description = "결제 Confirm 응답 DTO")
public record PaymentConfirmResponse(
    @Schema(description = "결제 데이터의 고유 식별자(PK)", example = "1") Long paymentId,
    @Schema(description = "결제 상태", example = "COMPLETED") String status,
    @Schema(description = "결제 완료 시각") @JsonSerialize(using = UtcLocalDateTimeSerializer.class)
        LocalDateTime paidAt) {}
