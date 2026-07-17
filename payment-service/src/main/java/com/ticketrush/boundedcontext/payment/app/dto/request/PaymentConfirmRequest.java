package com.ticketrush.boundedcontext.payment.app.dto.request;

import com.ticketrush.boundedcontext.payment.app.support.PaymentKeyFormat;
import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

@Schema(description = "결제 Confirm 요청 DTO")
public record PaymentConfirmRequest(
    @Schema(description = "예매 ID", example = "1") @NotNull(message = "예매 ID는 필수입니다.") @Positive
        Long bookingId,
    @Schema(description = "좌석 ID", example = "1") @NotNull(message = "좌석 ID는 필수입니다.") @Positive
        Long seatId,
    @Schema(description = "결제 수단", example = "KAKAO") @NotNull(message = "결제 수단은 필수입니다.")
        PaymentProvider provider,
    @Schema(description = "결제 금액", example = "55000") @NotNull(message = "결제 금액은 필수입니다.") @Positive
        Long amount,
    @Schema(description = "PG사가 발급한 결제 키", example = "tossKey_xxxxxxxx")
        @NotBlank(message = "결제 키는 필수입니다.")
        @Pattern(regexp = PaymentKeyFormat.REGEX, message = "결제 키 형식이 올바르지 않습니다.")
        String paymentKey) {}
