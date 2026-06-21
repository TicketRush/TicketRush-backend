package com.ticketrush.boundedcontext.payment.out.apiclient;

import com.ticketrush.boundedcontext.payment.domain.types.PaymentProvider;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 등록된 {@link PaymentCancelClient} 중 요청 provider를 지원하는 첫 번째 구현체로 결제 취소를 위임한다.
 *
 * <p>Stub은 {@link org.springframework.core.annotation.Order} 가장 낮은 우선순위로 등록되어 실제 provider 구현체가 우선
 * 선택되도록 한다.
 */
@Component
@RequiredArgsConstructor
public class PaymentCancelClientRouter {

  private final List<PaymentCancelClient> clients;

  public PaymentCancelResult cancel(PaymentCancelCommand command) {
    return resolve(command.provider()).cancel(command);
  }

  private PaymentCancelClient resolve(PaymentProvider provider) {
    return clients.stream()
        .filter(client -> client.supports(provider))
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorStatus.PAYMENT_PROVIDER_NOT_SUPPORTED));
  }
}
