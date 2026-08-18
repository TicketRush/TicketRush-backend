package com.ticketrush.boundedcontext.payment.out.apiclient;

import java.time.LocalDateTime;

/**
 * PG 승인 결과의 provider 중립 표현.
 *
 * <p>{@code method}는 사용자가 무엇으로 결제했는지(카드·간편결제 등)를 담는 한글 원문이며, 어느 PG사를 태웠는지를 뜻하는 {@code provider}와는
 * 다른 축이다(#593). PG가 내려주지 않으면 null이고, 그 경우에도 승인은 성공으로 다룬다 — 결제수단은 보존 대상일 뿐 승인 성립 요건이 아니다.
 */
public record PaymentApprovalResponse(
    String approvalNumber, Long approvedAmount, LocalDateTime approvedAt, String method) {}
