package com.ticketrush.boundedcontext.payment.out.apiclient;

import java.time.LocalDateTime;

public record PaymentApprovalResponse(
    String approvalNumber, Long approvedAmount, LocalDateTime approvedAt) {}
