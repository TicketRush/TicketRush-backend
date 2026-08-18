package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;

/**
 * Toss Payments 결제 승인 응답 (필요 필드만 매핑).
 *
 * <p>전체 응답 명세: https://docs.tosspayments.com/reference#payment-객체
 *
 * <p>{@code method}는 사용자가 무엇으로 결제했는지를 뜻하는 <b>한글 원문</b>
 * 문자열이다(카드/가상계좌/간편결제/휴대폰/계좌이체/문화상품권/도서문화상품권/게임문화상품권). PG사를 뜻하는 {@link
 * PaymentApprovalRequest#provider()}와는 다른 축이므로 혼동하지 않는다(#593). Toss 명세상 nullable이라 값이 없어도 승인은 성공으로
 * 다룬다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossConfirmResponse(
    String paymentKey,
    String orderId,
    String transactionKey,
    Long totalAmount,
    String status,
    OffsetDateTime approvedAt,
    String method) {}
