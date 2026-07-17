package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;

/**
 * Toss Payments 결제 조회 응답 (필요 필드만 매핑).
 *
 * <p>{@code GET /v1/payments/{paymentKey}} 응답. status는 READY/IN_PROGRESS/WAITING_FOR_DEPOSIT/DONE/
 * CANCELED/PARTIAL_CANCELED/ABORTED/EXPIRED 중 하나다.
 *
 * <p>전체 응답 명세: https://docs.tosspayments.com/reference#payment-객체
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPaymentInquiryResponse(
    String paymentKey,
    String orderId,
    Long totalAmount,
    String status,
    OffsetDateTime approvedAt) {}
