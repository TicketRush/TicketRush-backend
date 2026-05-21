package com.ticketrush.boundedcontext.payment.out.apiclient;

/**
 * PG사 결제 승인 API 호출용 클라이언트.
 *
 * <p>본 인터페이스는 #21에서 stub 구현체와 함께 도입되고, #89에서 실제 외부 PG사 연동 구현체로 교체된다.
 */
public interface PaymentApprovalClient {

  PaymentApprovalResponse approve(PaymentApprovalRequest request);
}
