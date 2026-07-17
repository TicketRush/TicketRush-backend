package com.ticketrush.boundedcontext.payment.out.apiclient;

import java.time.LocalDateTime;

/**
 * PG 결제 조회 결과 (provider 중립 DTO).
 *
 * <p>Webhook 재조회 검증에서 "PG에 실제 존재하는 결제인지"와 그 권위 있는 상태를 확인하는 데 사용한다. {@code approvedAt}은 승인 완료(DONE)가
 * 아닌 상태에서는 null일 수 있다.
 */
public record PaymentInquiryResult(
    String paymentKey, String orderId, Long totalAmount, String status, LocalDateTime approvedAt) {}
