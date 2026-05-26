package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;

/**
 * PG사 결제 승인 API 호출용 클라이언트.
 *
 * <p>구현체는 {@link #supports(PaymentProvider)}로 자신이 처리 가능한 PG를 알리고, {@link
 * PaymentApprovalClientRouter}가 요청 provider에 맞는 클라이언트를 선택해 호출한다.
 */
public interface PaymentApprovalClient {

  boolean supports(PaymentProvider provider);

  PaymentApprovalResponse approve(PaymentApprovalRequest request);
}
