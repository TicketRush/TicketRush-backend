package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 실 PG 호출 없이 요청 금액을 그대로 승인된 것으로 응답하는 stub 구현.
 *
 * <p>{@code payment.pg.stub.enabled=true} 일 때만 활성화된다. {@link PaymentApprovalClientRouter}에서 실제
 * provider별 구현체가 우선 선택되도록 {@link Order} 우선순위는 가장 낮게 둔다.
 */
@Slf4j
@Component
@Order(Integer.MAX_VALUE)
@ConditionalOnProperty(
    prefix = "payment.pg.stub",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public class StubPaymentApprovalClient implements PaymentApprovalClient {

  @Override
  public boolean supports(PaymentProvider provider) {
    return true;
  }

  @Override
  public PaymentApprovalResponse approve(PaymentApprovalRequest request) {
    log.info(
        "[PG-STUB] provider={}, orderId={}, bookingId={}, amount={}, paymentKey={}",
        request.provider(),
        request.orderId(),
        request.bookingId(),
        request.amount(),
        mask(request.paymentKey()));

    return new PaymentApprovalResponse(
        UUID.randomUUID().toString(), request.amount(), LocalDateTime.now());
  }

  private String mask(String value) {
    if (value == null || value.length() <= 4) {
      return "****";
    }
    return value.substring(0, 4) + "****";
  }
}
