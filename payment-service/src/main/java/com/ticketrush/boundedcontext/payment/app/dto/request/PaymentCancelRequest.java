package com.ticketrush.boundedcontext.payment.app.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "결제 취소(환불) 요청 DTO")
public record PaymentCancelRequest(
    @Schema(description = "환불 사유", example = "단순 변심")
        @NotBlank(message = "환불 사유는 필수입니다.")
        @Size(max = 200, message = "환불 사유는 200자 이하여야 합니다.")
        String reason) {}
