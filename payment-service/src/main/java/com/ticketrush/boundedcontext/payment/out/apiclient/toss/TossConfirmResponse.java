package com.ticketrush.boundedcontext.payment.out.apiclient.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;

/**
 * Toss Payments 결제 승인 응답 (필요 필드만 매핑).
 *
 * <p>전체 응답 명세: https://docs.tosspayments.com/reference#payment-객체
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossConfirmResponse(
    String paymentKey,
    String orderId,
    String transactionKey,
    Long totalAmount,
    String status,
    OffsetDateTime approvedAt) {}
