package com.ticketrush.boundedcontext.payment.out.apiclient.toss;

/**
 * Toss Payments 결제 승인 API 요청 페이로드.
 *
 * <p>참고: https://docs.tosspayments.com/reference#결제-승인
 */
public record TossConfirmRequest(String paymentKey, String orderId, Long amount) {}
