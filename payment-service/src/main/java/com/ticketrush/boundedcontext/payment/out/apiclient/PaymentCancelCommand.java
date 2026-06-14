package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;

/**
 * PG사 결제 취소 요청 커맨드.
 *
 * <p>{@code idempotencyKey}는 PG 측 중복 취소 방지를 위한 멱등 키로, 동일 결제의 재요청 시 같은 값을 전달한다.
 */
public record PaymentCancelCommand(
    PaymentProvider provider,
    String paymentKey,
    Long amount,
    String reason,
    String idempotencyKey) {}
