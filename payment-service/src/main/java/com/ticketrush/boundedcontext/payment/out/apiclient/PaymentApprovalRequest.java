package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;

public record PaymentApprovalRequest(
    PaymentProvider provider, String paymentKey, String orderId, Long bookingId, Long amount) {}
