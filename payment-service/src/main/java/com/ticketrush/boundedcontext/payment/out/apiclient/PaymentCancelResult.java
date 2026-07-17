package com.ticketrush.boundedcontext.payment.out.apiclient;

import java.time.LocalDateTime;

public record PaymentCancelResult(
    String pgRefundKey, Long refundedAmount, LocalDateTime canceledAt) {}
