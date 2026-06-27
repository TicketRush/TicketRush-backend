package com.ticketrush.boundedcontext.payment.app.dto.response;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "결제 내역 목록 응답 DTO")
public record PaymentSummaryResponse(
    @Schema(description = "결제 데이터의 고유 식별자(PK)", example = "1") Long paymentId,
    @Schema(description = "예매 ID", example = "12") Long bookingId,
    @Schema(description = "결제 수단", example = "TOSS") PaymentProvider provider,
    @Schema(description = "결제 금액", example = "55000") Long amount,
    @Schema(description = "결제 상태", example = "COMPLETED") PaymentStatus status,
    @Schema(description = "결제 완료 시각") LocalDateTime paidAt) {}
