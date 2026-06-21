package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 실 PG 호출 없이 요청 금액을 그대로 취소된 것으로 응답하는 stub 구현.
 *
 * <p>{@code payment.pg.stub.enabled=true} 일 때만 활성화된다. {@link PaymentCancelClientRouter}에서 실제
 * provider별 구현체가 우선 선택되도록 {@link Order} 우선순위는 가장 낮게 둔다.
 *
 * <p>운영(prod) 프로파일에서는 환경변수 설정과 무관하게 절대 활성화되지 않는다.
 */
@Slf4j
@Component
@Profile("!prod")
@Order(Integer.MAX_VALUE)
@ConditionalOnProperty(
    prefix = "payment.pg.stub",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public class StubPaymentCancelClient implements PaymentCancelClient {

  @Override
  public boolean supports(PaymentProvider provider) {
    return true;
  }

  @Override
  public PaymentCancelResult cancel(PaymentCancelCommand command) {
    log.info(
        "[PG-STUB] cancel provider={}, amount={}, paymentKey={}, idempotencyKey={}",
        command.provider(),
        command.amount(),
        mask(command.paymentKey()),
        command.idempotencyKey());

    return new PaymentCancelResult(
        UUID.randomUUID().toString(), command.amount(), LocalDateTime.now());
  }

  private String mask(String value) {
    if (value == null || value.length() <= 4) {
      return "****";
    }
    return value.substring(0, 4) + "****";
  }
}
