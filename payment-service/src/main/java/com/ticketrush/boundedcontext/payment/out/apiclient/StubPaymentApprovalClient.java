package com.ticketrush.boundedcontext.payment.out.apiclient;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * #21 범위의 stub 구현. 실제 PG 호출 없이 요청 금액을 그대로 승인된 것으로 응답한다.
 *
 * <p>#89에서 실제 외부 PG사 연동 구현체로 교체될 예정.
 */
@Slf4j
@Component
public class StubPaymentApprovalClient implements PaymentApprovalClient {

  @Override
  public PaymentApprovalResponse approve(PaymentApprovalRequest request) {
    log.info(
        "[PG-STUB] provider={}, bookingId={}, amount={}, paymentKey={}",
        request.provider(),
        request.bookingId(),
        request.amount(),
        request.paymentKey());

    return new PaymentApprovalResponse(
        UUID.randomUUID().toString(), request.amount(), LocalDateTime.now());
  }
}
