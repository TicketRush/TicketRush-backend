package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;

/**
 * PG사 결제 취소 요청 커맨드.
 *
 * <p>{@code idempotencyKey}는 PG 측 중복 취소 방지를 위한 멱등 키로, 동일 결제의 재요청 시 같은 값을 전달한다.
 *
 * <p>{@code amount}는 전액 환불 금액으로 Stub 응답·로깅 및 취소 금액 검증에 사용된다. #22는 전액 취소만 지원하므로 Toss 취소 호출 시에는
 * {@code cancelAmount}를 생략(전액 취소)하며 amount를 PG로 전송하지 않는다. (부분 취소 지원 시 cancelAmount 전송 추가 필요)
 */
public record PaymentCancelCommand(
    PaymentProvider provider,
    String paymentKey,
    Long amount,
    String reason,
    String idempotencyKey) {}
