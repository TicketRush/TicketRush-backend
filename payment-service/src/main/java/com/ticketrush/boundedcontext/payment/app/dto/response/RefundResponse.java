package com.ticketrush.boundedcontext.payment.app.dto.response;

import com.ticketrush.boundedcontext.payment.domain.types.RefundStatus;
import com.ticketrush.global.json.UtcLocalDateTimeSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

@Schema(description = "환불 정보 응답 DTO")
public record RefundResponse(
    @Schema(description = "환불 데이터의 고유 식별자(PK)", example = "1") Long refundId,
    @Schema(description = "환불 금액", example = "55000") Long price,
    @Schema(description = "환불 상태", example = "COMPLETED") RefundStatus status,
    @Schema(description = "환불 확정 시각") @JsonSerialize(using = UtcLocalDateTimeSerializer.class)
        LocalDateTime confirmedAt) {}
