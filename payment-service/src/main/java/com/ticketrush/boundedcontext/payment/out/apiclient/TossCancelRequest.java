package com.ticketrush.boundedcontext.payment.out.apiclient;

/**
 * Toss Payments 결제 취소 API 요청 페이로드.
 *
 * <p>{@code cancelAmount}를 생략하면 전액 취소로 처리된다. (#22는 전액 환불만 다룸)
 *
 * <p>참고: https://docs.tosspayments.com/reference#결제-취소
 */
public record TossCancelRequest(String cancelReason) {}
